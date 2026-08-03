package com.depromeet.piki.extractor.common.exception;

import java.util.Objects;

/**
 * 추출 계약 실패의 공통 부모. 계약(docs/api-contract.md)의 실패 응답을 {@code code()} 와
 * {@code permanent()} 로 표현한다 — 확정/일시 분류의 정본은 정적 팩토리에 박힌 permanent 플래그이고,
 * message 는 로그·cause 용이라 응답 body 로 나가지 않는다.
 */
public abstract class ExtractionException extends RuntimeException {

    private final ExtractionErrorCode code;
    private final boolean permanent;

    protected ExtractionException(String message, ExtractionErrorCode code, boolean permanent, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code");
        this.permanent = permanent;
    }

    public ExtractionErrorCode code() {
        return code;
    }

    public boolean permanent() {
        return permanent;
    }
}
