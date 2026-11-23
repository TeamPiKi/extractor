package com.depromeet.piki.extractor.extraction.rule;

import com.depromeet.piki.extractor.domain.ProductSnapshot;
import com.depromeet.piki.extractor.domain.ProductSnapshotException;
import com.depromeet.piki.extractor.extraction.PageContent;
import com.depromeet.piki.extractor.extraction.rule.RuleReading.State;
import com.depromeet.piki.extractor.extraction.structured.StructuredExtraction;
import java.util.Objects;
import tools.jackson.databind.ObjectMapper;

/**
 * 규칙이 읽은 값을 공통 규칙이 읽은 값 위에 얹은 결과. 합치는 규칙의 정본은 TeamPiKi/infra 의
 * contracts/extraction-api.md 7절이다.
 *
 * @param discarded 규칙의 값을 전부 버렸다. merged 는 null 이고 선언도 적용하지 않는다.
 * @param noDiscountApplied "할인 없음" 선언으로 정가를 판매가와 같게 채웠다.
 * @param merged READY 필드가 빌 수 있다.
 */
public record RuleApplication(
    MallRule rule,
    RuleReading reading,
    boolean discarded,
    boolean noDiscountApplied,
    ProductSnapshot merged
) {

    public static RuleApplication apply(
        MallRule rule,
        PageContent page,
        StructuredExtraction common,
        ObjectMapper objectMapper
    ) {
        RuleReading reading = rule.read(page.document(), objectMapper);
        if (reading.brokenRequiredField()) {
            return discarded(rule, reading);
        }
        ProductSnapshot base = common instanceof StructuredExtraction.Extracted extracted ? extracted.snapshot() : null;
        Integer currentPrice = reading.currentPrice().isRead()
            ? reading.currentPrice().value()
            : base == null ? null : base.currentPrice();
        boolean commonHasOriginal = base != null && base.originalPrice() != null;
        // 판매가가 공통 규칙의 것과 다르면 공통 규칙의 정가는 다른 가격 쌍의 것일 수 있다
        Integer commonOriginal = commonHasOriginal && Objects.equals(base.currentPrice(), currentPrice)
            ? base.originalPrice()
            : null;
        Integer ruleOriginal = reading.originalPrice().value();
        boolean ruleOriginalTrusted = ruleOriginal != null && currentPrice != null && ruleOriginal >= currentPrice;
        if (ruleOriginal != null && !ruleOriginalTrusted) {
            reading = reading.withOriginalPriceInvalid();
        }
        Integer originalPrice = ruleOriginalTrusted ? ruleOriginal : commonOriginal;
        // 공통 규칙이 정가를 찾은 문서는 "정가 자리가 없다" 가 아니다. 그 정가를 못 쓰는 경우에도 선언은 접는다
        boolean noDiscountApplied = rule.noDiscountWhenAbsent()
            && reading.originalPrice().state() != State.INVALID
            && !commonHasOriginal
            && originalPrice == null
            && currentPrice != null;
        try {
            ProductSnapshot merged = ProductSnapshot.fromExtracted(
                page.link(),
                reading.name().isRead() ? reading.name().value() : base == null ? null : base.name(),
                reading.imageUrl().isRead() ? reading.imageUrl().value() : base == null ? null : base.imageUrl(),
                currentPrice,
                noDiscountApplied ? currentPrice : originalPrice,
                base == null ? null : base.currency()
            );
            return new RuleApplication(rule, reading, false, noDiscountApplied, merged);
        } catch (ProductSnapshotException e) {
            return discarded(rule, reading);
        }
    }

    private static RuleApplication discarded(MallRule rule, RuleReading reading) {
        return new RuleApplication(rule, reading, true, false, null);
    }
}
