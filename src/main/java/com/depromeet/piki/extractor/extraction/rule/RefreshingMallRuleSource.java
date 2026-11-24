package com.depromeet.piki.extractor.extraction.rule;

import com.depromeet.piki.contracts.extraction.v1.ExtractionRuleSet;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 배포본을 주기적으로 읽어 메모리에 둔다. 요청 경로는 메모리의 값만 본다 — 이 서비스가 요청 사이에 들고 있는
 * 유일한 상태이고, 못 읽었을 때의 규칙은 TeamPiKi/infra 의 contracts/extraction-api.md 7절이 정본이다.
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class RefreshingMallRuleSource implements MallRuleSource {

    private final RuleSnapshotReader reader;
    private final MallRuleProperties properties;

    private volatile MallRules current = MallRules.NONE;
    /** 같은 문자열이면 다시 해석하지 않는다. 스케줄러 스레드만 만진다. */
    private String loadedSnapshot;
    private boolean loaded;
    private ScheduledExecutorService scheduler;

    @Override
    public MallRules current() {
        return current;
    }

    /** 첫 읽기도 스케줄러 스레드에서 한다 — Redis 가 느리거나 없다고 부팅과 헬스체크가 늦어지면 안 된다. */
    @PostConstruct
    void start() {
        if (!properties.enabled()) {
            log.info("extract rules source=none");
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("mall-rules-refresh").factory());
        scheduler.scheduleWithFixedDelay(
            this::refresh, 0, properties.refreshInterval().toMillis(), TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    /** 스케줄러가 예외로 멈추지 않게 전부 여기서 받는다. 실패하면 직전 배포본을 그대로 둔다. */
    void refresh() {
        try {
            String raw = reader.read().orElse(null);
            if (loaded && Objects.equals(raw, loadedSnapshot)) {
                return;
            }
            MallRules next = raw == null ? MallRules.NONE : MallRules.compile(parse(raw));
            current = next;
            loadedSnapshot = raw;
            loaded = true;
            log.info("extract rules loaded version={} rules={}", next.version(), next.size());
        } catch (RuntimeException e) {
            log.warn(
                "extract rules refresh failed keeping version={} rules={} cause={}",
                current.version(),
                current.size(),
                e.toString()
            );
        } catch (Throwable e) {
            // 여기서 새어 나가면 스케줄러가 다음 실행을 영영 걸지 않아 규칙이 지금 값으로 굳는다
            log.error("extract rules refresh crashed keeping version={}", current.version(), e);
        }
    }

    private static ExtractionRuleSet parse(String raw) {
        ExtractionRuleSet.Builder builder = ExtractionRuleSet.newBuilder();
        try {
            JsonFormat.parser().ignoringUnknownFields().merge(raw, builder);
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalStateException("배포본을 해석하지 못했다", e);
        }
        return builder.build();
    }
}
