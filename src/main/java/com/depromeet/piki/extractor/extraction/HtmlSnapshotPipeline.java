package com.depromeet.piki.extractor.extraction;

import com.depromeet.piki.extractor.domain.ExtractionMethod;
import com.depromeet.piki.extractor.domain.ProductSnapshot;
import com.depromeet.piki.extractor.domain.ProductSnapshotException;
import com.depromeet.piki.extractor.extraction.gemini.GeminiApiException;
import com.depromeet.piki.extractor.extraction.structured.StructuredDataExtractor;
import com.depromeet.piki.extractor.extraction.structured.StructuredExtraction;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.OptionalInt;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Component;

/**
 * HTML → ProductSnapshot 의 공통 후반부: 구조화 데이터(JSON-LD/OpenGraph) 우선, 미달이면 같은 HTML 을 Gemini 로
 * 넘긴다(재fetch 없음). 구조화로 끝났어도 정가가 비면 같은 HTML 로 정가만 Gemini 에 보강한다. 단 LLM 이 볼 게 아무것도 없는 문서({@link LlmInputGate})는 넘기지 않고 실패로
 * 끊는다 — 빈 입력의 LLM 은 실존하지 않는 상품을 지어내고, 그 값은 형식이 유효해 하류 검증을 전부 통과한다.
 * HTML 을 어디서 얻었는지(정적 fetch, 헤드리스 렌더)와 무관한 파싱 파이프라인이라 두 전략이 공유한다.
 *
 * <p>추출 방법을 카운터로 집계한다 — {@code via=structured} 대 {@code via=llm} 의 비율이 곧 비싼 LLM 호출을
 * 얼마나 줄였는지의 비용 지표이고, {@code via=skipped_shell} 이 게이트가 아낀 호출 수다. fallback 은 reason
 * 라벨로 사유를 분해해 "직접 파싱 적중률을 올리려면 어디를 보강할지"를 본다. application 태그는
 * {@code management.metrics.tags} 가 자동 부착한다.
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class HtmlSnapshotPipeline {

    private static final String EXTRACT_METRIC = "product.extract";
    private static final String TAG_VIA = "via";
    private static final String TAG_REASON = "reason";
    private static final String VIA_STRUCTURED = "structured";
    private static final String VIA_SKIPPED_SHELL = "skipped_shell";
    private static final String VIA_LLM = "llm";
    private static final String REASON_NONE = "none";

    private final StructuredDataExtractor structuredDataExtractor;
    private final GeminiHtmlExtractor geminiHtmlExtractor;
    private final MeterRegistry meterRegistry;

    /**
     * @param timing 호출 전략이 채우는 로그 조각({@code "fetch=123ms"} / {@code "render=5534ms"}) — HTML 획득 비용을
     *     extract 원장 로그 한 줄에 함께 남긴다. 전략 무관 파이프라인이 유일하게 전략을 아는 지점이라 해석하지 않는
     *     문자열로만 받는다.
     * @param model 호출자가 지정한 LLM 모델(없으면 빈 문자열). 구조화 파싱으로 끝나면 쓰이지 않고, LLM fallback 으로
     *     내려갈 때만 소비된다.
     */
    public ProductSnapshot extract(PageContent page, String timing, String model) {
        return extract(page, structuredDataExtractor.extract(page.document(), page.link()), timing, model);
    }

    /** 호출 전략이 후보 선별에 쓴 구조화 결과를 그대로 받는다 — 같은 Document 를 두 번 파싱하지 않는다. */
    public ProductSnapshot extract(PageContent page, StructuredExtraction result, String timing, String model) {
        // 수신 단계가 이미 파싱해 둔 Document 를 구조화 파서·게이트·Gemini fallback 이 그대로 공유한다.
        // baseUri 는 html 의 출처인 최종 URL 기준 — redirect 를 따라갔으면 원본 link 와 host 가 다를 수 있다.
        Document document = page.document();

        // 게이트 판정은 sanitize(GeminiHtmlExtractor) 전이어야 한다 — sanitize 는 공유 Document 에서 script 를
        // 제거하므로, 순서가 뒤집히면 데이터 script 존재 판정이 깨진다.
        boolean nothingForLlm = result instanceof StructuredExtraction.Miss && LlmInputGate.hasNothingForLlm(document);

        countExtract(result, nothingForLlm);

        return switch (result) {
            case StructuredExtraction.Extracted extracted -> structuredSnapshot(extracted, page, timing, model);
            case StructuredExtraction.Miss miss when nothingForLlm -> throw skippedShell(miss, page, timing);
            case StructuredExtraction.Miss miss -> llmSnapshot(miss, document, page, timing, model);
        };
    }

    /**
     * 카운터는 이 한 곳에서 항상 {@code {via, reason}} 두 키로 발행한다 — 경로마다 태그 키가 갈라지면 Prometheus 가
     * 같은 메트릭 이름의 뒤 시계열을 조용히 드롭한다(라벨 키 집합 불일치). Miss 계열은 실행 전에 올려, LLM 이
     * 실패해도 "직접 파싱으로 못 끝내 LLM 에 의존한 비율"에 포함되게 한다. 전략(plain/headless) 라벨은 두지
     * 않는다 — 헤드리스 볼륨의 관측은 escalation 메트릭·render 로그가 진다.
     */
    private void countExtract(StructuredExtraction result, boolean nothingForLlm) {
        String via = switch (result) {
            case StructuredExtraction.Extracted extracted -> VIA_STRUCTURED;
            case StructuredExtraction.Miss miss -> nothingForLlm ? VIA_SKIPPED_SHELL : VIA_LLM;
        };
        String reason = result instanceof StructuredExtraction.Miss miss ? miss.reason() : REASON_NONE;
        meterRegistry.counter(EXTRACT_METRIC, TAG_VIA, via, TAG_REASON, reason).increment();
    }

    private ProductSnapshot structuredSnapshot(
        StructuredExtraction.Extracted extracted,
        PageContent page,
        String timing,
        String model
    ) {
        log.info(
            "extract via=structured {} html={}chars url={}",
            timing,
            page.retainedChars(),
            page.link().safeLogString()
        );
        // 출처 표기는 값 생산자(파서·LLM)가 아니라 여기서 — finalUrl 을 아는 유일한 층이고,
        // 각 분기가 method 를 확정하는 지점이라 표기가 갈라질 수 없다.
        ProductSnapshot snapshot = extracted.snapshot().withOrigin(page.finalUrl(), ExtractionMethod.STRUCTURED);
        return enrichOriginalPrice(snapshot, page, model);
    }

    /**
     * 정가는 READY 필드가 아니라 비어도 LLM fallback 으로 안 넘어가므로 여기서 따로 묻는다. method 는 STRUCTURED
     * 그대로 — 판매가의 출처가 구조화 데이터다.
     */
    private ProductSnapshot enrichOriginalPrice(ProductSnapshot snapshot, PageContent page, String model) {
        // READY 미달은 호출 전략이 헤드리스로 승격해 다시 추출하므로 여기서 보강해도 버려진다
        if (snapshot.missingReadyField() || snapshot.originalPrice() != null) {
            return snapshot;
        }
        if (ExtractionDeadline.passed()) {
            log.info("extract originalPrice via=llm result=skipped_deadline url={}", page.link().safeLogString());
            return snapshot;
        }
        int currentPrice = snapshot.currentPrice();
        // sanitize 가 Document 를 변형하기 전에 판정한다
        boolean priceVisible = VisiblePrices.contains(page.document(), currentPrice);
        String remaining = ExtractionDeadline.remainingMillis().isPresent()
            ? ExtractionDeadline.remainingMillis().getAsLong() + "ms"
            : "none";
        long llmStart = System.nanoTime();
        try {
            OptionalInt enriched = geminiHtmlExtractor.extractOriginalPrice(
                page.document(), page.link(), model, currentPrice);
            boolean unverifiedSingle = enriched.isPresent() && enriched.getAsInt() == currentPrice && !priceVisible;
            ProductSnapshot enrichedSnapshot = unverifiedSingle
                ? snapshot
                : snapshot.withOriginalPrice(enriched.isPresent() ? enriched.getAsInt() : null);
            // null(근거 없음)·rejected(판매가 미만)·unverified_single(본문에 안 보이는 판매가를 정가로 단정)을 갈라 둔다 —
            // 프롬프트 품질은 이 로그로만 본다
            log.info(
                "extract originalPrice via=llm result={} discounted={} llm={}ms remaining={} url={}",
                unverifiedSingle ? "unverified_single" : enrichmentResult(enriched, enrichedSnapshot),
                enrichedSnapshot.originalPrice() != null && enrichedSnapshot.originalPrice() > currentPrice,
                (System.nanoTime() - llmStart) / 1_000_000,
                remaining,
                page.link().safeLogString()
            );
            return enrichedSnapshot;
        } catch (GeminiApiException e) {
            log.warn(
                "extract originalPrice via=llm result=failed llm={}ms url={} cause={}",
                (System.nanoTime() - llmStart) / 1_000_000,
                page.link().safeLogString(),
                e.toString()
            );
            return snapshot;
        }
    }

    private static String enrichmentResult(OptionalInt enriched, ProductSnapshot enrichedSnapshot) {
        if (enriched.isEmpty()) {
            return "null";
        }
        return enrichedSnapshot.originalPrice() == null ? "rejected" : "filled";
    }

    /**
     * 게이트 발동 원장 — url(host 포함)을 남기는 이유는 오탐 감시다: "가시 텍스트도 데이터 script 도 없는 정상
     * 상품 페이지"가 실재하면 그 몰의 host 가 이 라인에 반복 등장하므로, 배포 후 분포로 판정을 조정한다.
     */
    private ProductSnapshotException skippedShell(StructuredExtraction.Miss miss, PageContent page, String timing) {
        log.info(
            "extract via=skipped_shell reason={} {} html={}chars url={}",
            miss.reason(),
            timing,
            page.retainedChars(),
            page.link().safeLogString()
        );
        return ProductSnapshotException.noExtractableContent();
    }

    private ProductSnapshot llmSnapshot(
        StructuredExtraction.Miss miss,
        Document document,
        PageContent page,
        String timing,
        String model
    ) {
        ExtractionDeadline.requireNotPassed("llm");
        long llmStart = System.nanoTime();
        ProductSnapshot snapshot = geminiHtmlExtractor.extract(document, page.link(), model);
        long llmMs = (System.nanoTime() - llmStart) / 1_000_000;
        log.info(
            "extract via=llm reason={} {} llm={}ms html={}chars url={}",
            miss.reason(),
            timing,
            llmMs,
            page.retainedChars(),
            page.link().safeLogString()
        );
        return snapshot.withOrigin(page.finalUrl(), ExtractionMethod.LLM);
    }
}
