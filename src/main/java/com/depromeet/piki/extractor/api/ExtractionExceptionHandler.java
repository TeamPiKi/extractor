package com.depromeet.piki.extractor.api;

import com.depromeet.piki.extractor.common.exception.ExtractionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

// 계약 예외 → 응답 3갈래 매핑 (docs/api-contract.md):
//   permanent=true  → 422 + {code}  (호출자가 즉시 FAILED)
//   permanent=false → 502 + {code}  (호출자가 PROCESSING 유지 후 recover 재시도)
// 그 외 예상 못한 예외(불변식 위반·버그)는 여기서 잡지 않는다 — Spring 기본 500 이 계약상 "일시 실패"로
// 떨어져 호출자의 bounded 재시도(attempt 상한 2)가 흡수한다. fail-safe 원칙과 정합.
@RestControllerAdvice
public class ExtractionExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ExtractionExceptionHandler.class);

    @ExceptionHandler(ExtractionException.class)
    public ResponseEntity<ExtractionFailureResponse> handleExtraction(ExtractionException e) {
        // 확정 실패(422)는 계약상 정상 결과라 info, 일시 실패(502)는 외부 의존성 문제라 warn.
        if (e.permanent()) {
            log.info("extraction failed permanently code={} message={}", e.code(), e.getMessage());
        } else {
            log.warn("extraction failed transiently code={} message={}", e.code(), e.getMessage(), e);
        }
        HttpStatus status = e.permanent() ? HttpStatus.UNPROCESSABLE_ENTITY : HttpStatus.BAD_GATEWAY;
        return ResponseEntity.status(status).body(new ExtractionFailureResponse(e.code()));
    }
}
