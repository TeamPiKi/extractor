package com.depromeet.piki.extractor.domain;

import com.depromeet.piki.extractor.common.exception.ExtractionErrorCode;
import com.depromeet.piki.extractor.common.exception.ExtractionException;

/** 전부 호출자가 재시도해도 얻을 것이 없는 사유라 확정 실패로 답한다. */
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

    /**
     * LLM 에 넘겨도 지어낼 뿐인 빈 문서(LlmInputGate) — NOT_PRODUCT_PAGE 와 code 를 나누는 이유는 호출자
     * 관측이다: "사용자가 상품 아닌 링크를 넣음"과 "몰을 우리가 못 읽음"이 한 code 로 섞이면 후자의 빈도를
     * 추적할 수 없다.
     */
    public static ProductSnapshotException noExtractableContent() {
        return new ProductSnapshotException("상품 정보를 읽을 수 없는 페이지예요.", ExtractionErrorCode.NO_EXTRACTABLE_CONTENT);
    }
}
