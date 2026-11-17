package com.depromeet.piki.extractor.extraction;

import com.depromeet.piki.extractor.domain.ProductSnapshot;
import java.util.Objects;

/**
 * plain 이 READY 로 끝냈지만 판매가가 본문 가시 텍스트에 없는 페이지 — 가격을 스크립트가 그리므로 취소선 정가도
 * 브라우저 렌더 뒤에야 보인다(유니클로 실측: 정적 HTML 에 정가가 아예 없다). 실패가 아니라 승격 제안이라
 * plain 결과를 싣고 간다. 판정은 {@link HtmlSnapshotPipeline.Result#originalPriceHidden()}.
 *
 * <p>전략 반환형을 넓히지 않고 예외로 넘기는 이유: escalatable {@code PageFetchException} 과 같은
 * "plain 에서 headless 로 넘김" 축이고, headless 전략에는 이 신호가 없다.
 */
final class OriginalPriceHiddenException extends RuntimeException {

    private final ProductSnapshot plainSnapshot;

    private OriginalPriceHiddenException(ProductSnapshot plainSnapshot) {
        // 흐름 제어 신호라 스택이 필요 없다
        super("정가는 렌더 뒤에야 보인다", null, false, false);
        this.plainSnapshot = Objects.requireNonNull(plainSnapshot, "plainSnapshot");
    }

    static OriginalPriceHiddenException behindRender(ProductSnapshot plainSnapshot) {
        return new OriginalPriceHiddenException(plainSnapshot);
    }

    ProductSnapshot plainSnapshot() {
        return plainSnapshot;
    }
}
