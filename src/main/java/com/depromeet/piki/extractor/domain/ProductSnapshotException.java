package com.depromeet.piki.extractor.domain;

import com.depromeet.piki.extractor.common.exception.ExtractionErrorCode;
import com.depromeet.piki.extractor.common.exception.ExtractionException;

/** 둘 다 호출자가 재시도해도 얻을 것이 없는 사유라 확정 실패로 답한다. */
public final class ProductSnapshotException extends ExtractionException {

    private ProductSnapshotException(String message, ExtractionErrorCode code) {
        super(message, code, true, null);
    }

    public static ProductSnapshotException notProductPage() {
        return new ProductSnapshotException("상품 페이지 링크만 등록할 수 있어요.", ExtractionErrorCode.NOT_PRODUCT_PAGE);
    }

    /** 정상 URL 이라도 LLM 이 비결정적으로 이상값을 낼 수 있으니, 불변식 위반이 아니라 계약 실패로 다룬다. */
    public static ProductSnapshotException untrustworthyValue() {
        return new ProductSnapshotException("상품 정보를 확인하지 못했어요.", ExtractionErrorCode.UNTRUSTWORTHY_VALUE);
    }
}
