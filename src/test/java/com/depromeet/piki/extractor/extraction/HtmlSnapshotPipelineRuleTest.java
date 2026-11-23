package com.depromeet.piki.extractor.extraction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.depromeet.piki.extractor.domain.ExtractionMethod;
import com.depromeet.piki.extractor.domain.ProductLink;
import com.depromeet.piki.extractor.domain.ProductSnapshot;
import com.depromeet.piki.extractor.extraction.gemini.GeminiExtractionResult;
import com.depromeet.piki.extractor.extraction.gemini.GeminiOriginalPriceResult;
import com.depromeet.piki.extractor.extraction.rule.MallRules;
import com.depromeet.piki.extractor.extraction.rule.RuleFixtures;
import com.depromeet.piki.extractor.extraction.structured.StructuredDataExtractor;
import com.depromeet.piki.extractor.extraction.structured.StructuredExtraction;
import com.depromeet.piki.extractor.support.StubGeminiClient;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** 몰별 규칙, 공통 규칙, LLM 순서와 층 사이의 값 보존(TeamPiKi/infra contracts/extraction-api.md 7절). */
class HtmlSnapshotPipelineRuleTest {

    private static final String RENDERED_NO_DISCOUNT = """
        <html><head><meta property="og:image" content="https://image.oliveyoung.co.kr/a.jpg"></head><body>
          <h3 class="GoodsDetailInfo_title__x9Zk2">라운드랩 독도 토너</h3>
          <div class="GoodsDetailInfo_price__7hJ3a"><span>15,900</span>원</div>
        </body></html>""";

    private static final String JSON_LD_WITHOUT_ORIGINAL = "<html><head><script type=\"application/ld+json\">"
        + "{\"@type\":\"Product\",\"name\":\"공통 규칙이 읽는 상품\",\"image\":\"https://cdn.example.com/p.png\","
        + "\"offers\":{\"price\":\"299000\",\"priceCurrency\":\"KRW\"}}"
        + "</script></head><body><p>299,000원</p></body></html>";

    private static final String PRICE_ONLY_RULE = """
        {"id": 7, "host": "oliveyoung.co.kr",
         "currentPrice": {"css": {"selector": ".price"}},
         "absentOriginalPrice": "NO_DISCOUNT"}""";

    private final ProductLink link = ProductLink.parse("https://www.oliveyoung.co.kr/store/goods/1");

    private final StubGeminiClient stubGemini = new StubGeminiClient();

    private HtmlSnapshotPipeline pipeline(MallRules rules) {
        return new HtmlSnapshotPipeline(
            new StructuredDataExtractor(new ObjectMapper()),
            new GeminiHtmlExtractor(stubGemini),
            new SimpleMeterRegistry(),
            () -> rules,
            new ObjectMapper()
        );
    }

    private ProductSnapshot extract(MallRules rules, String html) {
        return pipeline(rules).extract(PageContent.of(link, html), "fetch=0ms", null);
    }

    @Test
    @DisplayName("구조화 데이터가 없는 화면도 규칙이 다 읽으면 LLM 없이 끝내고, 정가 요소가 없으면 할인 없음으로 채운다")
    void ruleAloneCompletesWithoutLlm() {
        ProductSnapshot snapshot = extract(RuleFixtures.rules(RuleFixtures.OLIVE_YOUNG), RENDERED_NO_DISCOUNT);

        assertEquals("라운드랩 독도 토너", snapshot.name());
        assertEquals("https://image.oliveyoung.co.kr/a.jpg", snapshot.imageUrl());
        assertEquals(15_900, snapshot.currentPrice());
        assertEquals(15_900, snapshot.originalPrice());
        assertEquals(ExtractionMethod.STRUCTURED, snapshot.method());
        assertEquals(0, stubGemini.invocations());
    }

    @Test
    @DisplayName("할인 없음 선언만 가진 규칙은 공통 규칙의 결과에 정가를 채워 정가 LLM 보강을 부르지 않는다")
    void declarationOnlyRuleSkipsOriginalPriceLlm() {
        ProductSnapshot snapshot = extract(
            RuleFixtures.rules("{\"id\": 4, \"host\": \"oliveyoung.co.kr\", \"absentOriginalPrice\": \"NO_DISCOUNT\"}"),
            JSON_LD_WITHOUT_ORIGINAL
        );

        assertEquals(299_000, snapshot.currentPrice());
        assertEquals(299_000, snapshot.originalPrice());
        assertEquals(0, stubGemini.invocations());
    }

    @Test
    @DisplayName("규칙이 출처를 적은 필드를 못 읽으면 규칙을 버리고 공통 규칙의 결과로 내려간다")
    void discardedRuleFallsBackToCommonRule() {
        stubGemini.build = request -> new GeminiOriginalPriceResult(null);

        ProductSnapshot snapshot = extract(RuleFixtures.rules(RuleFixtures.OLIVE_YOUNG), JSON_LD_WITHOUT_ORIGINAL);

        assertEquals("공통 규칙이 읽는 상품", snapshot.name());
        assertEquals(299_000, snapshot.currentPrice());
        // 버린 규칙의 할인 없음 선언은 적용되지 않는다
        assertNull(snapshot.originalPrice());
    }

    @Test
    @DisplayName("규칙이 판매가만 읽고 나머지를 LLM 이 채우면 판매가와 정가는 규칙의 것을 지키고 method 는 LLM 이다")
    void llmFillsOnlyWhatRuleLeftBlank() {
        stubGemini.build = request -> new GeminiExtractionResult(
            true, "LLM 이 읽은 이름", 12_000, 30_000, "KRW", "https://cdn.example.com/llm.png");

        ProductSnapshot snapshot = extract(
            RuleFixtures.rules(PRICE_ONLY_RULE),
            "<html><body><h1>상품 설명이 충분히 긴 본문</h1><div class=\"price\">15,900원</div></body></html>"
        );

        assertEquals("LLM 이 읽은 이름", snapshot.name());
        assertEquals("https://cdn.example.com/llm.png", snapshot.imageUrl());
        assertEquals(15_900, snapshot.currentPrice());
        assertEquals(15_900, snapshot.originalPrice());
        assertEquals(ExtractionMethod.LLM, snapshot.method());
        assertEquals(1, stubGemini.invocations());
    }

    @Test
    @DisplayName("규칙이 하나도 못 읽고 LLM 이 판매가를 읽으면 할인 없음 선언을 적용하지 않는다")
    void declarationDoesNotApplyToLlmPrice() {
        stubGemini.build = request -> new GeminiExtractionResult(
            true, "LLM 이 읽은 이름", 12_000, null, "KRW", "https://cdn.example.com/llm.png");

        ProductSnapshot snapshot = extract(
            RuleFixtures.rules("{\"id\": 4, \"host\": \"oliveyoung.co.kr\", \"absentOriginalPrice\": \"NO_DISCOUNT\"}"),
            "<html><body><h1>상품 설명이 충분히 긴 본문</h1><p>12,000원</p></body></html>"
        );

        assertEquals(12_000, snapshot.currentPrice());
        assertNull(snapshot.originalPrice());
        assertEquals(ExtractionMethod.LLM, snapshot.method());
    }

    @Test
    @DisplayName("렌더 지정 규칙은 렌더하지 않은 문서에 적용하지 않는다 - 아직 안 그려진 정가 자리를 할인 없음으로 읽지 않는다")
    void renderRuleIsNotAppliedToUnrenderedDocument() {
        HtmlSnapshotPipeline pipeline = pipeline(RuleFixtures.rules(RuleFixtures.OLIVE_YOUNG));
        PageContent page = PageContent.of(link, RENDERED_NO_DISCOUNT);

        HtmlSnapshotPipeline.Parsed unrendered = pipeline.parse(page, false);
        HtmlSnapshotPipeline.Parsed rendered = pipeline.parse(page, true);

        assertNull(unrendered.applied());
        assertInstanceOf(StructuredExtraction.Miss.class, unrendered.result());
        assertTrue(rendered.applied().noDiscountApplied());
        assertInstanceOf(StructuredExtraction.Extracted.class, rendered.result());
    }

    @Test
    @DisplayName("다른 몰의 규칙은 적용하지 않는다")
    void ruleForAnotherHostIsIgnored() {
        stubGemini.build = request -> new GeminiOriginalPriceResult(null);

        ProductSnapshot snapshot = extract(
            RuleFixtures.rules("{\"id\": 9, \"host\": \"29cm.co.kr\", \"absentOriginalPrice\": \"NO_DISCOUNT\"}"),
            JSON_LD_WITHOUT_ORIGINAL
        );

        assertNull(snapshot.originalPrice());
    }
}
