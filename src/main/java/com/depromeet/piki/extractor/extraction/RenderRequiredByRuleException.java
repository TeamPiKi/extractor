package com.depromeet.piki.extractor.extraction;

import com.depromeet.piki.extractor.domain.ProductSnapshot;
import java.util.function.Supplier;

/**
 * 정적 fetch 로 받은 문서가 "렌더한 문서에서 읽으라" 는 몰별 규칙의 대상이다. 실패가 아니라 흐름 제어다 —
 * 진입점({@link FallbackProductLinkExtractor})이 받아 렌더로 넘긴다.
 */
public class RenderRequiredByRuleException extends RuntimeException {

    private final long ruleId;
    private final transient Supplier<ProductSnapshot> withoutRender;

    RenderRequiredByRuleException(long ruleId, Supplier<ProductSnapshot> withoutRender) {
        super(null, null, false, false);
        this.ruleId = ruleId;
        this.withoutRender = withoutRender;
    }

    long ruleId() {
        return ruleId;
    }

    /** 렌더를 못 쓸 때 받아 둔 정적 문서로 끝낸다. */
    ProductSnapshot withoutRender() {
        return withoutRender.get();
    }
}
