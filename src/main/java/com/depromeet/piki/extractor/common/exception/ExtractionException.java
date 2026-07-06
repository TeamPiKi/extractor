package com.depromeet.piki.extractor.common.exception;

import java.util.Objects;

// 추출 계약 실패의 공통 부모. PIKI-Server 의 BaseException + HttpMappable(category·httpStatus) 역할을
// 이 서비스의 계약(응답 3갈래)에 맞게 번역한 것이다:
//   permanent=true  → 422 + code (확정 실패, 호출자가 즉시 FAILED)
//   permanent=false → 502 + code (일시 실패, 호출자가 PROCESSING 유지 후 recover 재시도)
// 원본의 ErrorCategory 매핑 규칙: RETRYABLE → permanent=false, SERVER_ERROR·INVALID_INPUT → permanent=true.
// message 는 로그·디버깅용이며 응답 body 에는 code 만 나간다 (내부 정보 비노출).
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
