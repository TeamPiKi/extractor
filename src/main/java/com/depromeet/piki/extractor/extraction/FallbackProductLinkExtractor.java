package com.depromeet.piki.extractor.extraction;

import com.depromeet.piki.extractor.domain.ProductLink;
import com.depromeet.piki.extractor.domain.ProductSnapshot;
import com.depromeet.piki.extractor.extraction.http.PageFetchException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 상품 URL 추출의 공개 진입점. 두 전략을 "plain 먼저, 차단되면 headless" 로 엮는다 — headless 는 비싸고 느려
 * plain 이 차단으로 막힌 경우에만 탄다.
 *
 * <p>에스컬레이션 축(plain 차단 → headless)은 호출자 outbox 의 재시도 축(일시 오류 → 같은 plain 재시도)과
 * 직교한다. 차단은 재시도 축에서 이미 확정 실패(422)라 그 슬롯(attemptCount)에 얹을 수 없다. 그래서 여기서
 * 별도로 판정한다.
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class FallbackProductLinkExtractor implements ProductLinkExtractor {

    private static final String ESCALATION_METRIC = "product.extract.escalation";
    /**
     * 직행 경로의 추세 축. 라벨 키는 {@code outcome} 뿐 — plain 실패가 없어 category 축이 존재하지 않는다
     * (메트릭 이름이 달라 escalation 의 {@code outcome, category} 키 집합과 충돌하지 않는다).
     */
    private static final String HEADLESS_FIRST_METRIC = "product.extract.headless_first";
    private static final String TAG_OUTCOME = "outcome";
    private static final String TAG_CATEGORY = "category";
    private static final String OUTCOME_SUCCESS = "success";
    private static final String OUTCOME_FAILED = "failed";
    private static final String CATEGORY_UNKNOWN = "unknown";

    /** 필드의 {@code @Qualifier} 는 lombok.config 의 copyableAnnotations 가 생성자 파라미터로 복사한다. */
    @Qualifier(LinkExtractionStrategy.PLAIN)
    private final LinkExtractionStrategy plain;
    @Qualifier(LinkExtractionStrategy.HEADLESS)
    private final LinkExtractionStrategy headless;
    private final MeterRegistry meterRegistry;
    private final HeadlessExtractionProperties headlessProperties;

    @Override
    public ProductSnapshot extract(ProductLink link, boolean headlessFirst) {
        // 호출자 정책이 이 서비스의 스위치를 앞설 수는 없다 — 스위치가 꺼져 있으면 headlessFirst 힌트도 무시한다.
        if (!headlessProperties.enabled()) {
            return plain.extract(link);
        }

        // 호출자의 브라우저 직행 정책 — plain 이 항상 차단되는 host 의 느린-실패(fetch 타임아웃을 다 기다린 뒤
        // 에스컬레이트) 낭비를 없앤다. 직행 실패는 plain 으로 되돌리지 않고 그대로 전파한다: 재시도는 호출자
        // 재시도 축이, 정책 오지정은 호출자 쪽 롤백이 진다.
        if (headlessFirst) {
            return extractHeadlessFirst(link);
        }

        try {
            return plain.extract(link);
        } catch (RuntimeException e) {
            if (!shouldEscalate(e)) {
                throw e;
            }
            return escalateToHeadless(link, e);
        }
    }

    /**
     * 브라우저 직행(정책 힌트). outcome 을 별도 카운터로 집계한다 — escalation 카운터는 에스컬레이션 축만
     * 커버해서, 직행 볼륨·성공률이 시계열에 없으면 호출자의 직행 정책 오지정(실제로는 plain 이 통하는 host)이
     * 로그 grep 전까지 조용히 지속된다(메트릭=추세, 로그=원장).
     */
    private ProductSnapshot extractHeadlessFirst(ProductLink link) {
        log.info("extract route=headless_first url={}", link.safeLogString());
        try {
            ProductSnapshot snapshot = headless.extract(link);
            headlessFirstCounter(OUTCOME_SUCCESS).increment();
            return snapshot;
        } catch (Throwable failure) {
            // escalateToHeadless 와 같은 이유로 Throwable — Error 실패도 집계에서 빠지지 않게 하고 그대로 rethrow.
            headlessFirstCounter(OUTCOME_FAILED).increment();
            log.warn(
                "extract route=headless_first outcome=failed cause={} url={}",
                failure.getClass().getSimpleName(),
                link.safeLogString()
            );
            throw failure;
        }
    }

    private Counter headlessFirstCounter(String outcome) {
        return meterRegistry.counter(HEADLESS_FIRST_METRIC, TAG_OUTCOME, outcome);
    }

    /**
     * plain 이 막혀 headless 로 넘긴다. 결과를 outcome 으로 집계하되 "어떤 실패가 escalate 됐나"를 category 로
     * 쪼갠다 — 무조건 폴백이라 낭비(특히 일시 오류를 헤드리스로 보냈는데 실패)가 생기므로, 그 비율을 category
     * 별로 봐서 후속 per-host 튜닝의 근거로 삼는다(메트릭=추세, host 로그=원장). headless 예외는 그대로 상위로
     * 전파해 API 계층의 계약 매핑에 맡긴다.
     *
     * <p>라벨 키 집합 {@code outcome, category} 는 이 카운터의 모든 발행 경로에서 동일해야 한다.
     */
    private ProductSnapshot escalateToHeadless(ProductLink link, RuntimeException plainFailure) {
        String category = categoryOf(plainFailure);
        log.info("extract escalate=headless plainCategory={} url={}", category, link.safeLogString());
        try {
            ProductSnapshot snapshot = headless.extract(link);
            escalationCounter(OUTCOME_SUCCESS, category).increment();
            return snapshot;
        } catch (Throwable headlessFailure) {
            // Exception 이 아니라 Throwable 을 잡는다: headless 구현이 미구현 오류나 OOM 등 Error 로 실패해도
            // outcome=failed 집계가 빠지지 않게. 여기선 기록만 하고 그대로 rethrow 하므로(swallow 아님) Error 의미는 보존된다.
            escalationCounter(OUTCOME_FAILED, category).increment();
            log.warn(
                "extract escalate=headless outcome=failed plainCategory={} headlessCause={} url={}",
                category,
                headlessFailure.getClass().getSimpleName(),
                link.safeLogString()
            );
            throw headlessFailure;
        }
    }

    private Counter escalationCounter(String outcome, String category) {
        return meterRegistry.counter(ESCALATION_METRIC, TAG_OUTCOME, outcome, TAG_CATEGORY, category);
    }

    /**
     * plain 실패를 headless 로 넘길지 판정한다. 어떤 실패가 넘길 수 있는지는 {@code PageFetchException.escalatable}
     * 이 단일 진실이다. 우리가 SSRF 로 막은 host 를 헤드리스로 다시 겨누는 것은 recall 문제가 아니라 SSRF
     * 취약점이므로, 그 경로는 escalatable 이 아니어서 절대 여기로 오지 않는다.
     */
    private boolean shouldEscalate(Throwable e) {
        return e instanceof PageFetchException pageFetchException && pageFetchException.escalatable();
    }

    private String categoryOf(Throwable plainFailure) {
        if (plainFailure instanceof PageFetchException pageFetchException) {
            return pageFetchException.code().name();
        }
        return CATEGORY_UNKNOWN;
    }
}
