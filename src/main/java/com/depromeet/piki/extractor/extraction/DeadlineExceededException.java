package com.depromeet.piki.extractor.extraction;

import com.depromeet.piki.extractor.common.exception.ExtractionErrorCode;
import com.depromeet.piki.extractor.common.exception.ExtractionException;

/** 호출자가 끊었을 시점이 지나 다음 단계를 시작하지 않은 실패. 같은 링크를 다시 하면 될 수 있어 일시 실패다. */
public final class DeadlineExceededException extends ExtractionException {

    private DeadlineExceededException(String message) {
        super(message, ExtractionErrorCode.UPSTREAM_ERROR, false, null);
    }

    static DeadlineExceededException before(String stage) {
        return new DeadlineExceededException("호출자 기한이 지나 " + stage + " 단계를 시작하지 않았다.");
    }
}
