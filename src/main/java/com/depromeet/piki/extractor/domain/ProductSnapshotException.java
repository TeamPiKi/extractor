package com.depromeet.piki.extractor.domain;

import com.depromeet.piki.extractor.common.exception.ExtractionErrorCode;
import com.depromeet.piki.extractor.common.exception.ExtractionException;

// 둘 다 재시도 무의미한 확정 실패(422).
public final class ProductSnapshotException extends ExtractionException {

    private ProductSnapshotException(String message, ExtractionErrorCode code) {
        super(message, code, true, null);
    }

    // LLM 이 "상품 페이지가 아님"으로 판정. 링크 재등록·재시도 모두 무의미.
    public static ProductSnapshotException notProductPage() {
        return new ProductSnapshotException("상품 페이지 링크만 등록할 수 있어요.", ExtractionErrorCode.NOT_PRODUCT_PAGE);
    }

    // 추출값이 유효 범위(가격 음수, 컬럼 길이 초과 등)를 벗어남. 추출 결과를 신뢰할 수 없다.
    // 정상 URL 이라도 LLM 이 비결정적으로 이상값을 낼 수 있는 계약 실패다.
    public static ProductSnapshotException untrustworthyValue() {
        return new ProductSnapshotException("상품 정보를 확인하지 못했어요.", ExtractionErrorCode.UNTRUSTWORTHY_VALUE);
    }
}
