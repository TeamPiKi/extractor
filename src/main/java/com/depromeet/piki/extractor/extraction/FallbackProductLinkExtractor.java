package com.depromeet.piki.extractor.extraction;

import com.depromeet.piki.extractor.domain.ProductLink;
import com.depromeet.piki.extractor.domain.ProductSnapshot;
import com.depromeet.piki.extractor.extraction.http.PageFetchException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

// PIKI-Server: product/service/FallbackProductLinkExtractor.kt 포팅.
// 상품 URL 추출의 공개 진입점(ProductLinkExtractor). 두 전략을 "우리 먼저, 막히면 헤드리스" 공식으로 엮는다:
//   1) plain(DefaultProductLinkExtractor)   : 정적 HTTP + 구조화/LLM. 싸고 빠른 기본 경로.
//   2) headless(HeadlessProductLinkExtractor): 차단 우회 브라우저. 비싸고 느려, plain 이 "차단"으로 막힌 경우에만 탄다.
//
// product.extract.headless.enabled 기본값이 false 인 동안은 plain 에 그대로 위임해 현재 동작과 100% 동일하다.
//
// 중요: 에스컬레이션 축(plain 차단 → headless)은 호출자의 outbox 재시도 축(일시 오류 → 같은 plain 재시도)과
// 직교한다. 차단은 재시도 축에서 이미 "확정 실패(422)"라 그 슬롯(attemptCount)에 얹을 수 없다. 그래서 여기서 별도 판정한다.
@Component
public class FallbackProductLinkExtractor implements ProductLinkExtractor {

    private static final String ESCALATION_METRIC = "product.extract.escalation";
    private static final String TAG_OUTCOME = "outcome";
    private static final String TAG_CATEGORY = "category";
    private static final String OUTCOME_SUCCESS = "success";
    private static final String OUTCOME_FAILED = "failed";
    private static final String CATEGORY_UNKNOWN = "unknown";

    private static final Logger log = LoggerFactory.getLogger(FallbackProductLinkExtractor.class);

    private final LinkExtractionStrategy plain;
    private final LinkExtractionStrategy headless;
    private final MeterRegistry meterRegistry;
    private final HeadlessExtractionProperties headlessProperties;

    public FallbackProductLinkExtractor(
        @Qualifier(LinkExtractionStrategy.PLAIN) LinkExtractionStrategy plain,
        @Qualifier(LinkExtractionStrategy.HEADLESS) LinkExtractionStrategy headless,
        MeterRegistry meterRegistry,
        HeadlessExtractionProperties headlessProperties
    ) {
        this.plain = plain;
        this.headless = headless;
        this.meterRegistry = meterRegistry;
        this.headlessProperties = headlessProperties;
    }

    @Override
    public ProductSnapshot extract(ProductLink link) {
        // 헤드리스가 꺼져 있으면(기본값) plain 에 그대로 위임한다 — 결과도 예외도 그대로 전파된다.
        if (!headlessProperties.enabled()) {
            return plain.extract(link);
        }

        // (확장 지점) 호출량 x 느린-실패 낭비가 큰 host 는 여기서 plain 을 건너뛰고 headless 로 직행하되, 소량(canary)만
        // plain 으로 흘려 차단 해제를 감시한다. host 별 정책·canary 비율은 헤드리스 구현·운영 메트릭이 확정할 때 붙인다.

        try {
            return plain.extract(link);
        } catch (RuntimeException e) {
            if (!shouldEscalate(e)) {
                throw e;
            }
            return escalateToHeadless(link, e);
        }
    }

    // plain 이 막혀 headless 로 넘긴다. 결과를 outcome 으로 집계하되 "어떤 실패가 escalate 됐나"를 category 로 쪼갠다 —
    // "무조건 폴백"이라 낭비(특히 일시 오류를 헤드리스로 보냈는데 실패)가 생기므로, 그 비율을 category 별로 봐서
    // 후속 per-host 튜닝의 근거로 삼는다(메트릭=추세, host 로그=원장). headless 예외는 그대로 상위로 전파해
    // API 계층의 계약 매핑(422/502)에 맡긴다.
    // (포팅 노트: 원본의 category 값은 ErrorCategory 명이었으나, 이 서비스엔 ErrorCategory 가 없어
    //  ExtractionErrorCode 명으로 대체했다. 라벨 키 집합 {outcome, category} 는 모든 발행 경로에서 동일하다.)
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

    // plain 실패를 headless 로 넘길지 판정한다. 어떤 fetch 실패가 그런지는 PageFetchException.escalatable 이 단일
    // 진실이다(무조건 폴백: SSRF 만 빼고 전부 true). SSRF 로 우리가 막은 host(blockedHost)는 escalatable=false 라
    // 절대 여기로 오지 않는다(내부망에 헤드리스를 겨누는 건 SSRF 취약점이라 recall 이 아니라 보안 문제).
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
