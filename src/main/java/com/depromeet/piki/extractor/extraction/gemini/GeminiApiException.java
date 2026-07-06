package com.depromeet.piki.extractor.extraction.gemini;

import com.depromeet.piki.extractor.common.exception.ExtractionErrorCode;
import com.depromeet.piki.extractor.common.exception.ExtractionException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClientResponseException;

// PIKI-Server: product/service/gemini/GeminiApiException.kt 포팅.
// 원본은 ErrorCategory(RETRYABLE / SERVER_ERROR) 로 재시도 여부를 갈랐다. 이 서비스엔 ErrorCategory 가 없어
// 계약 매핑 규칙(RETRYABLE → permanent=false 일시 실패, SERVER_ERROR → permanent=true 확정 실패)으로 번역하고,
// code 는 각각 LLM_UPSTREAM / LLM_INVALID_RESPONSE 로 대응한다.
// message 는 로그·디버깅용이며 응답 body 에는 code 만 나간다 — 원본의 고정 사용자 문구를 그대로 유지한다.
public final class GeminiApiException extends ExtractionException {

    private static final String USER_MESSAGE = "정보를 불러오지 못했어요. 잠시 후 다시 시도해 주세요.";

    private GeminiApiException(ExtractionErrorCode code, boolean permanent, Throwable cause) {
        super(USER_MESSAGE, code, permanent, cause);
    }

    // 원본 RETRYABLE. transport/네트워크 등 일시 장애로 보고 재시도 대상(permanent=false).
    public static GeminiApiException upstreamError(Throwable cause) {
        return new GeminiApiException(ExtractionErrorCode.LLM_UPSTREAM, false, cause);
    }

    // 원본 SERVER_ERROR. 재시도해도 의미 없는 확정 실패(permanent=true).
    public static GeminiApiException clientError(Throwable cause) {
        return new GeminiApiException(ExtractionErrorCode.LLM_INVALID_RESPONSE, true, cause);
    }

    // HTTP 에러 응답을 분류한다.
    // 5xx · 429(Too Many Requests) · 408(Request Timeout) 은 일시 장애로 보고 재시도 대상(upstreamError),
    // 그 외 4xx(잘못된 키·요청 등) 는 재시도해도 의미 없으므로 확정 실패(clientError).
    public static GeminiApiException fromResponseError(RestClientResponseException e) {
        HttpStatusCode status = e.getStatusCode();
        boolean retryable =
            status.is5xxServerError()
                || status.value() == HttpStatus.TOO_MANY_REQUESTS.value()
                || status.value() == HttpStatus.REQUEST_TIMEOUT.value();
        return retryable ? upstreamError(e) : clientError(e);
    }

    // body 자체가 없음 (역직렬화 이전). transport/인프라 이슈로 일시적일 가능성이 크므로 재시도 대상.
    public static GeminiApiException emptyResponse() {
        return new GeminiApiException(ExtractionErrorCode.LLM_UPSTREAM, false, null);
    }

    public static GeminiApiException parseError(Throwable cause) {
        return new GeminiApiException(ExtractionErrorCode.LLM_INVALID_RESPONSE, true, cause);
    }

    // 스키마는 유효하지만 candidates/parts 가 빈 리스트. safety filter 등 정책적 거부 가능성이 높아 재시도 무의미.
    public static GeminiApiException noTextPart() {
        return new GeminiApiException(ExtractionErrorCode.LLM_INVALID_RESPONSE, true, null);
    }
}
