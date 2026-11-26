package com.depromeet.piki.extractor.stream;

import com.depromeet.piki.extractor.common.exception.ExtractionErrorCode;
import com.depromeet.piki.extractor.common.exception.ExtractionException;

/** 추출을 시작하지 못하고 요청을 닫는 실패. 요청을 다시 보내면 될 수 있어 일시 실패다. */
public final class ParseRequestException extends ExtractionException {

    private ParseRequestException(String message) {
        super(message, ExtractionErrorCode.DELIVERY_EXHAUSTED, false, null);
    }

    public static ParseRequestException deliveryExhausted() {
        return new ParseRequestException("배달 횟수 상한까지 꺼내 가고도 끝내지 못했다.");
    }

    public static ParseRequestException unknownInputType() {
        return new ParseRequestException("입력 종류를 읽지 못했다.");
    }

    public static ParseRequestException imageBucketMissing() {
        return new ParseRequestException("정책 배포본에 이미지 버킷이 없다.");
    }
}
