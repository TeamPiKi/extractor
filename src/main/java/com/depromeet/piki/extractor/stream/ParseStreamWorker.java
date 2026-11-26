package com.depromeet.piki.extractor.stream;

import com.depromeet.piki.extractor.stream.ParseStream.Abandoned;
import com.depromeet.piki.extractor.stream.ParseStream.Delivery;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;

/**
 * 한 환경의 Redis 에서 요청을 꺼내 처리한다. 전달 보장과 이어받기의 규칙은 TeamPiKi/infra 의
 * contracts/extraction-api.md 8절이 정본이다.
 *
 * <p>자리(permit)를 먼저 잡고 꺼낸다. 꺼낸 뒤에 자리를 기다리면 그 요청이 처리도 안 된 채 이어받기 기준을 넘긴다.
 * 자리는 넘긴 작업이 끝날 때 {@link #dispatch} 한 곳에서만 돌려준다.
 */
@Slf4j
final class ParseStreamWorker {

    private static final int RECLAIM_LIMIT = 100;

    private final String source;
    private final ParseStream stream;
    private final ParseRequestProcessor processor;
    private final ParseStreamProperties properties;
    private final Executor executor;
    /** 순서를 지킨다. 읽기 스레드가 자리를 놓자마자 다시 잡으므로, 순서가 없으면 이어받기가 자리를 얻지 못한다. */
    private final Semaphore permits;

    private volatile ExtractionPolicySnapshot policy = ExtractionPolicySnapshot.DEFAULT;
    /** 같은 문자열이면 다시 해석하지 않는다. */
    private String loadedPolicy;
    private boolean policyLoaded;

    ParseStreamWorker(
        String source,
        ParseStream stream,
        ParseRequestProcessor processor,
        ParseStreamProperties properties,
        Executor executor
    ) {
        this.source = source;
        this.stream = stream;
        this.processor = processor;
        this.properties = properties;
        this.executor = executor;
        this.permits = new Semaphore(properties.concurrency(), true);
    }

    /** @return 읽기에 실패했으면 false. 부르는 쪽이 쉬었다 다시 부른다. */
    boolean pollOnce() throws InterruptedException {
        permits.acquire();
        boolean handedOver = false;
        try {
            for (Delivery delivery : stream.readNew(properties.readBlock())) {
                dispatch(() -> handle(delivery));
                handedOver = true;
            }
            return true;
        } catch (RuntimeException e) {
            // 종료가 대기 중인 읽기를 끊은 것이면 실패가 아니다
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException();
            }
            log.warn("parse stream read failed source={} cause={}", source, e.toString());
            return false;
        } finally {
            if (!handedOver) {
                permits.release();
            }
        }
    }

    /** 기동 직후와 읽기가 실패한 뒤에 부른다. 스트림이 통째로 사라진 경우는 그룹을 다시 만들어야 풀린다. */
    void prepare() {
        try {
            stream.ensureGroup();
            stream.removeIdleConsumers(properties.staleConsumerAfter());
        } catch (RuntimeException e) {
            log.warn("parse stream prepare failed source={} cause={}", source, e.toString());
        }
    }

    void reclaimOnce() {
        try {
            for (Abandoned abandoned : stream.findAbandoned(ParseStreamContract.REDELIVERY_AFTER, RECLAIM_LIMIT)) {
                // 읽기 스레드가 한 번의 대기를 끝내고 자리를 놓을 때까지는 기다린다
                if (!permits.tryAcquire(properties.commandTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                    return;
                }
                boolean handedOver = false;
                try {
                    handedOver = takeOver(abandoned);
                } finally {
                    if (!handedOver) {
                        permits.release();
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            log.warn("parse stream reclaim failed source={} cause={}", source, e.toString());
        }
    }

    synchronized void refreshPolicy() {
        try {
            String raw = stream.readPolicy().orElse(null);
            if (policyLoaded && Objects.equals(raw, loadedPolicy)) {
                return;
            }
            ExtractionPolicySnapshot next = raw == null ? ExtractionPolicySnapshot.DEFAULT : ExtractionPolicySnapshot.parse(raw);
            policy = next;
            loadedPolicy = raw;
            policyLoaded = true;
            log.info(
                "extract policy loaded source={} version={} blockedDomains={} authorizedDomains={} imageBucket={}",
                source,
                next.version(),
                next.blockedDomains().values().size(),
                next.authorizedDomains().values().size(),
                next.hasImageBucket()
            );
        } catch (RuntimeException e) {
            log.warn("extract policy refresh failed source={} keeping version={} cause={}", source, policy.version(), e.toString());
        }
    }

    void stopReading() {
        stream.stopReading();
    }

    void close() {
        stream.close();
    }

    /** @return 처리를 넘겼으면 true. */
    private boolean takeOver(Abandoned abandoned) {
        Optional<Delivery> claimed = stream.claim(abandoned.id(), ParseStreamContract.REDELIVERY_AFTER);
        if (claimed.isEmpty()) {
            return false;
        }
        Delivery delivery = claimed.get();
        if (abandoned.deliveries() >= ParseStreamContract.MAX_DELIVERIES) {
            dispatch(() -> finish(delivery, processor.exhausted(delivery.fields(), source)));
        } else {
            dispatch(() -> handle(delivery));
        }
        return true;
    }

    /** 잡아 둔 자리를 작업에 넘긴다. 실행기가 받지 않으면 던지고, 자리는 부른 쪽이 돌려준다. */
    private void dispatch(Runnable work) {
        executor.execute(() -> {
            try {
                work.run();
            } finally {
                permits.release();
            }
        });
    }

    private void handle(Delivery delivery) {
        try {
            finish(delivery, processor.process(delivery.fields(), policy, source));
        } catch (RuntimeException e) {
            log.error("parse request crashed source={} message={} 한 번 더 배달되기를 기다린다", source, delivery.id(), e);
        }
    }

    /** 결과를 쓴 뒤에 확인 응답을 보낸다. 순서가 뒤집히면 그 사이에 죽었을 때 요청이 결과 없이 사라진다. */
    private void finish(Delivery delivery, Optional<Map<String, String>> result) {
        try {
            if (result.isPresent()) {
                stream.publishResult(result.get());
            } else {
                log.warn("parse request unreadable source={} message={} fields={}", source, delivery.id(), delivery.fields().keySet());
            }
            stream.acknowledge(delivery.id());
        } catch (RuntimeException e) {
            log.warn("parse result publish failed source={} message={} cause={}", source, delivery.id(), e.toString());
        }
    }
}
