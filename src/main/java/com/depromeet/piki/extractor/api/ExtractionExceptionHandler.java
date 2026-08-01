package com.depromeet.piki.extractor.api;

import com.depromeet.piki.extractor.common.exception.ExtractionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 계약 예외를 응답으로 매핑한다 (docs/api-contract.md).
 *
 * <p>그 외 예상 못한 예외(불변식 위반·버그)는 일부러 잡지 않는다 — Spring 기본 500 이 계약상 "일시 실패"로
 * 떨어져 호출자의 bounded 재시도가 흡수하므로 fail-safe 원칙과 정합이다.
 */
@Slf4j
@RestControllerAdvice
public class ExtractionExceptionHandler {

    @ExceptionHandler(ExtractionException.class)
    public ResponseEntity<ExtractionFailureResponse> handleExtraction(ExtractionException e) {
        // 확정 실패는 계약상 정상 결과라 info, 일시 실패는 외부 의존성 문제라 warn.
        if (e.permanent()) {
            log.info("extraction failed permanently code={} message={}", e.code(), e.getMessage());
        } else {
            log.warn("extraction failed transiently code={} message={}", e.code(), e.getMessage(), e);
        }
        HttpStatus status = e.permanent() ? HttpStatus.UNPROCESSABLE_ENTITY : HttpStatus.BAD_GATEWAY;
        return ResponseEntity.status(status).body(new ExtractionFailureResponse(e.code()));
    }
}
