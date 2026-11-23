package com.depromeet.piki.extractor.extraction.rule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.depromeet.piki.extractor.domain.ProductLink;
import com.depromeet.piki.extractor.domain.ProductSnapshot;
import com.depromeet.piki.extractor.extraction.PageContent;
import com.depromeet.piki.extractor.extraction.structured.StructuredExtraction;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class RuleApplicationTest {

    private static final ProductLink LINK = ProductLink.parse("https://www.example.com/p/1");

    private static final String PRICE_RULE = """
        {"host": "example.com",
         "currentPrice": {"css": {"selector": ".price"}},
         "originalPrice": {"css": {"selector": ".before"}},
         "absentOriginalPrice": "NO_DISCOUNT"}""";

    private static final String DECLARATION_ONLY = """
        {"host": "example.com", "absentOriginalPrice": "NO_DISCOUNT"}""";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private RuleApplication apply(String ruleJson, String body, StructuredExtraction common) {
        return RuleApplication.apply(
            RuleFixtures.rule(ruleJson),
            PageContent.of(LINK, "<html><body>" + body + "</body></html>"),
            common,
            objectMapper
        );
    }

    private static StructuredExtraction common(Integer currentPrice, Integer originalPrice) {
        return new StructuredExtraction.Extracted(ProductSnapshot.fromExtracted(
            LINK, "공통 규칙 이름", "https://img.example.com/a.jpg", currentPrice, originalPrice, "KRW"));
    }

    @Test
    @DisplayName("규칙이 읽은 필드는 규칙의 값을, 출처를 적지 않은 필드는 공통 규칙의 값을 쓴다")
    void ruleValuesOverlayCommonValues() {
        RuleApplication applied = apply(
            PRICE_RULE,
            "<div class=\"price\">15,900원</div><div class=\"before\">20,000원</div>",
            common(15_900, null)
        );

        assertFalse(applied.discarded());
        assertEquals("공통 규칙 이름", applied.merged().name());
        assertEquals(15_900, applied.merged().currentPrice());
        assertEquals(20_000, applied.merged().originalPrice());
        assertEquals("KRW", applied.merged().currency());
    }

    @Test
    @DisplayName("출처를 적은 판매가를 못 읽으면 규칙의 값을 전부 버리고 할인 없음 선언도 적용하지 않는다")
    void brokenRequiredFieldDiscardsRule() {
        RuleApplication applied = apply(PRICE_RULE, "<p>품절</p>", common(15_900, null));

        assertTrue(applied.discarded());
        assertFalse(applied.noDiscountApplied());
        assertNull(applied.merged());
        assertEquals(List.of("currentPrice", "originalPrice"), applied.reading().unreadFields());
    }

    @Test
    @DisplayName("정가 요소가 없고 할인 없음 선언이 있으면 정가를 판매가와 같게 채운다")
    void absentOriginalPriceBecomesNoDiscount() {
        RuleApplication applied = apply(
            PRICE_RULE, "<div class=\"price\">15,900원</div>", StructuredExtraction.Miss.NO_DATA);

        assertTrue(applied.noDiscountApplied());
        assertEquals(15_900, applied.merged().originalPrice());
    }

    @Test
    @DisplayName("정가 요소는 있는데 금액을 못 읽으면 할인 없음으로 채우지 않고 정가를 모름으로 둔다")
    void invalidOriginalPriceIsNotNoDiscount() {
        RuleApplication applied = apply(
            PRICE_RULE,
            "<div class=\"price\">15,900원</div><div class=\"before\">20,000 18,000</div>",
            StructuredExtraction.Miss.NO_DATA
        );

        assertFalse(applied.discarded());
        assertFalse(applied.noDiscountApplied());
        assertNull(applied.merged().originalPrice());
    }

    @Test
    @DisplayName("읽은 정가가 판매가보다 작으면 값 불량으로 보고 할인 없음으로 채우지 않는다")
    void originalBelowCurrentIsNotNoDiscount() {
        RuleApplication applied = apply(
            PRICE_RULE,
            "<div class=\"price\">15,900원</div><div class=\"before\">9,900원</div>",
            StructuredExtraction.Miss.NO_DATA
        );

        assertFalse(applied.noDiscountApplied());
        assertNull(applied.merged().originalPrice());
        assertEquals(List.of("originalPrice"), applied.reading().unreadFields());
    }

    @Test
    @DisplayName("공통 규칙이 다른 판매가의 정가를 찾은 문서에서는 그 정가도 쓰지 않고 할인 없음으로도 채우지 않는다")
    void declarationYieldsWhenCommonFoundAnyOriginalPrice() {
        RuleApplication applied = apply(
            PRICE_RULE, "<div class=\"price\">15,900원</div>", common(18_000, 25_000));

        assertFalse(applied.noDiscountApplied());
        assertEquals(15_900, applied.merged().currentPrice());
        assertNull(applied.merged().originalPrice());
    }

    @Test
    @DisplayName("정가 출처가 없는 규칙의 할인 없음 선언은 공통 규칙이 정가를 못 찾았을 때만 적용한다")
    void declarationOnlyAppliesWhenCommonHasNoOriginalPrice() {
        RuleApplication withoutOriginal = apply(DECLARATION_ONLY, "<p>본문</p>", common(299_000, null));
        RuleApplication withOriginal = apply(DECLARATION_ONLY, "<p>본문</p>", common(299_000, 350_000));

        assertTrue(withoutOriginal.noDiscountApplied());
        assertEquals(299_000, withoutOriginal.merged().originalPrice());
        assertFalse(withOriginal.noDiscountApplied());
        assertEquals(350_000, withOriginal.merged().originalPrice());
    }

    @Test
    @DisplayName("판매가를 규칙도 공통 규칙도 못 읽었으면 할인 없음 선언을 적용하지 않는다")
    void declarationNeedsPriceFromRuleOrCommon() {
        RuleApplication applied = apply(DECLARATION_ONLY, "<p>본문</p>", StructuredExtraction.Miss.NO_DATA);

        assertFalse(applied.noDiscountApplied());
        assertTrue(applied.merged().hasNoExtractedValue());
    }

    @Test
    @DisplayName("규칙의 판매가가 공통 규칙의 판매가와 다르면 공통 규칙의 정가를 가져오지 않는다")
    void doesNotBorrowOriginalPriceFromAnotherPricePair() {
        RuleApplication applied = apply(
            """
            {"host": "example.com", "currentPrice": {"css": {"selector": ".price"}}}""",
            "<div class=\"price\">15,900원</div>",
            common(18_000, 25_000)
        );

        assertEquals(15_900, applied.merged().currentPrice());
        assertNull(applied.merged().originalPrice());
    }
}
