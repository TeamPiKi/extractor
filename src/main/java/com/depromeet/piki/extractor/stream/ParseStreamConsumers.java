package com.depromeet.piki.extractor.stream;

import com.depromeet.piki.extractor.extraction.ExtractionDeadline;
import com.depromeet.piki.extractor.extraction.HeadlessExtractionProperties;
import com.depromeet.piki.extractor.extraction.gemini.GeminiHttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * 설정의 Redis 마다 소비자를 하나씩 돌린다. 읽기와 주기 작업은 전부 전용 스레드에서 한다 — Redis 가 느리거나 없다고
 * 부팅과 헬스체크가 늦어지면 안 된다.
 */
@Slf4j
@Component
public class ParseStreamConsumers implements SmartLifecycle {

    private final ParseStreamProperties properties;
    private final ParseStreamConnector connector;
    private final ParseRequestProcessor processor;

    private final List<ParseStreamWorker> workers = new ArrayList<>();
    private final List<Thread> readers = new ArrayList<>();
    private ExecutorService handlers;
    private ScheduledExecutorService scheduler;
    private volatile boolean running;

    public ParseStreamConsumers(
        ParseStreamProperties properties,
        ParseStreamConnector connector,
        ParseRequestProcessor processor,
        HeadlessExtractionProperties headless
    ) {
        Duration llmCall = Duration.ofMillis(GeminiHttpClient.READ_TIMEOUT_MS);
        Duration render = headless.connectTimeout().plus(headless.readTimeout());
        ParseStreamContract.requireHonored(
            ExtractionDeadline.linkBudget(), llmCall.compareTo(render) > 0 ? llmCall : render, properties.reclaimInterval());
        this.properties = properties;
        this.connector = connector;
        this.processor = processor;
    }

    @Override
    public void start() {
        running = true;
        if (properties.sources().isEmpty()) {
            log.info("parse stream sources=none");
            return;
        }
        String consumerName = "extractor-" + UUID.randomUUID();
        handlers = Executors.newVirtualThreadPerTaskExecutor();
        // Redis 하나가 느려도 다른 환경의 주기 작업이 밀리지 않게 환경마다 스레드를 준다
        scheduler = Executors.newScheduledThreadPool(
            properties.sources().size(), Thread.ofPlatform().daemon().name("parse-stream-schedule-", 0).factory());
        for (ParseStreamProperties.Source source : properties.sources()) {
            ParseStreamWorker worker = new ParseStreamWorker(
                source.name(), connector.connect(source, consumerName), processor, properties, handlers);
            workers.add(worker);
            long policyInterval = properties.policyRefreshInterval().toMillis();
            long reclaimInterval = properties.reclaimInterval().toMillis();
            scheduler.scheduleWithFixedDelay(guarded(worker::refreshPolicy), policyInterval, policyInterval, TimeUnit.MILLISECONDS);
            scheduler.scheduleWithFixedDelay(guarded(worker::reclaimOnce), reclaimInterval, reclaimInterval, TimeUnit.MILLISECONDS);
            Thread reader = Thread.ofPlatform().daemon().name("parse-stream-read-" + source.name()).unstarted(() -> read(worker));
            readers.add(reader);
            reader.start();
        }
        log.info("parse stream sources={} consumer={}", properties.sources(), consumerName);
    }

    /**
     * 새 요청 읽기를 먼저 끊고, 돌던 추출이 결과를 쓰기를 {@code stopTimeout} 까지 기다린다. 그 안에 끝나지 못한 요청은
     * 확인 응답 없이 남아 다른 슬롯이 이어받는다 — 추출 한 건이 배포의 종료 유예보다 길 수 있다.
     */
    @Override
    public void stop() {
        running = false;
        readers.forEach(Thread::interrupt);
        workers.forEach(ParseStreamWorker::stopReading);
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        if (handlers != null) {
            handlers.shutdown();
            awaitHandlers();
        }
        workers.forEach(ParseStreamWorker::close);
        workers.clear();
        readers.clear();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void awaitHandlers() {
        try {
            if (!handlers.awaitTermination(properties.stopTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                log.info("parse stream stopped with requests in flight, they will be redelivered");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 정책을 읽은 뒤에 첫 요청을 꺼낸다. 순서가 뒤집히면 기동 직후의 요청이 기본 정책으로 처리된다. */
    private void read(ParseStreamWorker worker) {
        worker.prepare();
        worker.refreshPolicy();
        Duration delay = properties.retryDelay();
        while (running) {
            try {
                if (worker.pollOnce()) {
                    delay = properties.retryDelay();
                    continue;
                }
                Thread.sleep(delay);
                delay = longer(delay);
                worker.prepare();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable e) {
                // 여기서 새어 나가면 이 환경의 요청을 영영 읽지 않는다
                log.error("parse stream reader crashed, continuing", e);
                delay = longer(delay);
            }
        }
    }

    private Duration longer(Duration delay) {
        Duration doubled = delay.multipliedBy(2);
        return doubled.compareTo(properties.maxRetryDelay()) > 0 ? properties.maxRetryDelay() : doubled;
    }

    /** 스케줄러가 예외로 다음 실행을 영영 걸지 않는 일을 막는다. */
    private static Runnable guarded(Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (Throwable e) {
                log.error("parse stream scheduled task crashed", e);
            }
        };
    }
}
