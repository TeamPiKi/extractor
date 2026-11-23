package com.depromeet.piki.extractor.extraction;

import com.depromeet.piki.extractor.domain.ExtractionMethod;
import com.depromeet.piki.extractor.domain.ProductSnapshot;
import com.depromeet.piki.extractor.domain.ProductSnapshotException;
import com.depromeet.piki.extractor.extraction.gemini.GeminiApiException;
import com.depromeet.piki.extractor.extraction.rule.MallRule;
import com.depromeet.piki.extractor.extraction.rule.MallRuleSource;
import com.depromeet.piki.extractor.extraction.rule.RuleApplication;
import com.depromeet.piki.extractor.extraction.structured.StructuredDataExtractor;
import com.depromeet.piki.extractor.extraction.structured.StructuredExtraction;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import java.util.OptionalInt;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * HTML → ProductSnapshot 의 공통 후반부: 구조화 데이터(JSON-LD/OpenGraph) 우선, 미달이면 같은 HTML 을 Gemini 로
 * 넘긴다(재fetch 없음). 구조화로 끝났어도 정가가 비면 같은 HTML 로 정가만 Gemini 에 보강한다(정적 fetch 전략은
 * 렌더로 볼 수 있는 페이지면 이 보강을 렌더 뒤로 미룬다). 단 LLM 이 볼 게 아무것도 없는 문서({@link LlmInputGate})는 넘기지 않고 실패로
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
    private final MallRuleSource mallRuleSource;
    private final ObjectMapper objectMapper;

    /**
     * 몰별 규칙과 공통 규칙으로 읽은 결과. LLM 은 아직 부르지 않았다.
     *
     * @param ruleKept 규칙이 일부 필드만 읽어 READY 에 못 미친 값. LLM 이 나머지를 채워도 이 값은 덮지 않는다.
     * @param applied 이 문서에 적용한 규칙. 없으면 null. 로그는 이 문서가 채택됐을 때만 남긴다 — 헤드리스 전략은
     *     버릴 후보(중간 홉·챌린지)도 파싱하므로 여기서 남기면 "몰의 구조가 바뀜" 경고가 오탐으로 울린다.
     */
    public record Parsed(StructuredExtraction result, ProductSnapshot ruleKept, RuleApplication applied) {
    }

    /**
     * 순서는 몰별 규칙, 공통 규칙이다(TeamPiKi/infra contracts/extraction-api.md 7절). 규칙이 못 읽으면 공통 규칙의
     * 결과로 내려가고 요청은 실패하지 않는다.
     *
     * @param renderedDom 브라우저가 스크립트를 실행한 뒤의 문서인가. 렌더 지정 규칙은 그런 문서에만 적용한다 —
     *     스크립트가 그리는 정가 자리가 아직 없는 문서에서 "자리 없음" 을 할인 없음으로 읽지 않기 위해서다.
     */
    public Parsed parse(PageContent page, boolean renderedDom) {
        StructuredExtraction common = structuredDataExtractor.extract(page.document(), page.link());
        Optional<MallRule> rule = mallRuleSource.current().forPage(page).filter(found -> renderedDom || !found.render());
        if (rule.isEmpty()) {
            return new Parsed(common, null, null);
        }
        RuleApplication applied = RuleApplication.apply(rule.get(), page, common, objectMapper);
        if (applied.discarded()) {
            return new Parsed(common, null, applied);
        }
        ProductSnapshot merged = applied.merged();
        if (!merged.missingReadyField() || common instanceof StructuredExtraction.Extracted) {
            return new Parsed(new StructuredExtraction.Extracted(merged), null, applied);
        }
        return new Parsed(common, merged.hasNoExtractedValue() ? null : merged, applied);
    }

    private static void logRule(RuleApplication applied, PageContent page) {
        if (applied == null) {
            return;
        }
        if (applied.discarded()) {
            // 몰의 구조가 바뀌었다는 신호다. 알림이 이 줄을 센다
            log.warn(
                "extract rule={} result=discarded unread={} url={}",
                applied.rule().id(),
                String.join("+", applied.reading().unreadFields()),
                page.link().safeLogString()
            );
            return;
        }
        log.info(
            "extract rule={} result=applied noDiscount={} missing={} url={}",
            applied.rule().id(),
            applied.noDiscountApplied(),
            applied.merged().missingFieldNames(),
            page.link().safeLogString()
        );
    }

    /**
     * @param timing 호출 전략이 채우는 로그 조각({@code "fetch=123ms"} / {@code "render=5534ms"}) — HTML 획득 비용을
     *     extract 원장 로그 한 줄에 함께 남긴다. 전략 무관 파이프라인이 유일하게 전략을 아는 지점이라 해석하지 않는
     *     문자열로만 받는다.
     * @param model 호출자가 지정한 LLM 모델(없으면 빈 문자열). 구조화 파싱으로 끝나면 쓰이지 않고, LLM fallback 으로
     *     내려갈 때만 소비된다.
     */
    public ProductSnapshot extract(PageContent page, String timing, String model) {
        return extract(page, parse(page, true), timing, model);
    }

    /** 호출 전략이 후보 선별에 쓴 결과를 그대로 받는다 — 같은 Document 를 두 번 파싱하지 않는다. */
    public ProductSnapshot extract(PageContent page, Parsed parsed, String timing, String model) {
        return run(page, parsed, timing, model, false).snapshot();
    }

    /**
     * @param originalPriceHidden 정가를 모르고 판매가가 본문 가시 텍스트에 없다 — 가격을 스크립트가 그리는 페이지라
     *     렌더 뒤에야 정가가 보인다.
     * @param originalPriceLlmDeferred 그래서 정가 LLM 을 아직 묻지 않았다. LLM 은 렌더까지 해 본 뒤의 마지막 수단이다.
     */
    record Result(ProductSnapshot snapshot, boolean originalPriceHidden, boolean originalPriceLlmDeferred) {
        private static Result settled(ProductSnapshot snapshot) {
            return new Result(snapshot, false, false);
        }
    }

    /** 정적 fetch 전략용 — 렌더로 정가를 볼 수 있는 페이지면 정가 LLM 을 미루고 그 사실을 함께 돌려준다. */
    Result extractDeferringHiddenOriginalPrice(PageContent page, String timing, String model) {
        return run(page, parse(page, false), timing, model, true);
    }

    /** 미뤄 둔 정가 LLM 을 지금 묻는다 — 렌더가 정가를 못 보탰을 때의 마지막 수단. */
    ProductSnapshot enrichDeferredOriginalPrice(ProductSnapshot snapshot, PageContent page, String model) {
        return enrichOriginalPrice(snapshot, page, model, false).snapshot();
    }

    private Result run(
        PageContent page,
        Parsed parsed,
        String timing,
        String model,
        boolean deferHiddenOriginalPrice
    ) {
        // 수신 단계가 이미 파싱해 둔 Document 를 구조화 파서·게이트·Gemini fallback 이 그대로 공유한다.
        // baseUri 는 html 의 출처인 최종 URL 기준 — redirect 를 따라갔으면 원본 link 와 host 가 다를 수 있다.
        Document document = page.document();
        logRule(parsed.applied(), page);

        // 게이트 판정은 sanitize(GeminiHtmlExtractor) 전이어야 한다 — sanitize 는 공유 Document 에서 script 를
        // 제거하므로, 순서가 뒤집히면 데이터 script 존재 판정이 깨진다.
        boolean shell = parsed.result() instanceof StructuredExtraction.Miss && LlmInputGate.hasNothingForLlm(document);
        // LLM 에 보여 줄 것이 없어도 규칙이 읽은 값이 있으면 그것으로 답한다 — 부분값은 성공이다
        StructuredExtraction result = shell && parsed.ruleKept() != null
            ? new StructuredExtraction.Extracted(parsed.ruleKept())
            : parsed.result();
        boolean nothingForLlm = shell && parsed.ruleKept() == null;

        countExtract(result, nothingForLlm);

        return switch (result) {
            case StructuredExtraction.Extracted extracted ->
                structuredSnapshot(extracted, page, timing, model, deferHiddenOriginalPrice);
            case StructuredExtraction.Miss miss when nothingForLlm -> throw skippedShell(miss, page, timing);
            case StructuredExtraction.Miss miss -> llmResult(
                keepingRuleValues(llmSnapshot(miss, document, page, timing, model), parsed.ruleKept()), document);
        };
    }

    /** 규칙이 판매가를 읽었으면 정가도 규칙 쪽 것만 쓴다 — LLM 의 정가는 LLM 이 본 판매가와 짝이다. */
    private static ProductSnapshot keepingRuleValues(ProductSnapshot llm, ProductSnapshot kept) {
        if (kept == null) {
            return llm;
        }
        boolean rulePrice = kept.currentPrice() != null;
        return new ProductSnapshot(
            llm.link(),
            kept.name() != null ? kept.name() : llm.name(),
            kept.imageUrl() != null ? kept.imageUrl() : llm.imageUrl(),
            rulePrice ? kept.currentPrice() : llm.currentPrice(),
            null,
            kept.currency() != null ? kept.currency() : llm.currency(),
            llm.finalUrl(),
            llm.method()
        ).withOriginalPrice(rulePrice ? kept.originalPrice() : llm.originalPrice());
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

    private static Result llmResult(ProductSnapshot snapshot, Document document) {
        boolean hidden = !snapshot.missingReadyField()
            && snapshot.originalPrice() == null
            && !VisiblePrices.contains(document, snapshot.currentPrice());
        return new Result(snapshot, hidden, false);
    }

    private Result structuredSnapshot(
        StructuredExtraction.Extracted extracted,
        PageContent page,
        String timing,
        String model,
        boolean deferHiddenOriginalPrice
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
        return enrichOriginalPrice(snapshot, page, model, deferHiddenOriginalPrice);
    }

    /**
     * 정가는 READY 필드가 아니라 비어도 LLM fallback 으로 안 넘어가므로 여기서 따로 묻는다. method 는 STRUCTURED
     * 그대로 — 판매가의 출처가 구조화 데이터다.
     */
    private Result enrichOriginalPrice(
        ProductSnapshot snapshot,
        PageContent page,
        String model,
        boolean deferHiddenOriginalPrice
    ) {
        // READY 미달은 호출 전략이 헤드리스로 승격해 다시 추출하므로 여기서 보강해도 버려진다
        if (snapshot.missingReadyField() || snapshot.originalPrice() != null) {
            return Result.settled(snapshot);
        }
        int currentPrice = snapshot.currentPrice();
        // sanitize 가 Document 를 변형하기 전에 판정한다
        boolean priceVisible = VisiblePrices.contains(page.document(), currentPrice);
        if (deferHiddenOriginalPrice && !priceVisible) {
            log.info("extract originalPrice via=llm result=deferred_render url={}", page.link().safeLogString());
            return new Result(snapshot, true, true);
        }
        if (ExtractionDeadline.passed()) {
            log.info("extract originalPrice via=llm result=skipped_deadline url={}", page.link().safeLogString());
            return Result.settled(snapshot);
        }
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
                "extract originalPrice via=llm result={} discounted={} priceVisible={} llm={}ms remaining={} url={}",
                unverifiedSingle ? "unverified_single" : enrichmentResult(enriched, enrichedSnapshot),
                enrichedSnapshot.originalPrice() != null && enrichedSnapshot.originalPrice() > currentPrice,
                priceVisible,
                (System.nanoTime() - llmStart) / 1_000_000,
                remaining,
                page.link().safeLogString()
            );
            return Result.settled(enrichedSnapshot);
        } catch (GeminiApiException e) {
            log.warn(
                "extract originalPrice via=llm result=failed llm={}ms url={} cause={}",
                (System.nanoTime() - llmStart) / 1_000_000,
                page.link().safeLogString(),
                e.toString()
            );
            return Result.settled(snapshot);
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
