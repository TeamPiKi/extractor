package com.depromeet.piki.extractor.extraction.gemini;

import com.depromeet.piki.extractor.common.exception.ExtractionErrorCode;
import com.depromeet.piki.extractor.common.exception.ExtractionException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClientResponseException;

/**
 * Gemini 호출 실패의 계약 예외. 일시/확정 분류를 이 클래스의 정적 팩토리에 모아 두고,
 * GeminiRetry 는 permanent 플래그만 보고 재시도를 판단한다.
 *
 * <p>message 는 로그·디버깅용 고정 문구이며 응답 body 에는 code 만 나간다.
 */
public final class GeminiApiException extends ExtractionException {

    private static final String USER_MESSAGE = "정보를 불러오지 못했어요. 잠시 후 다시 시도해 주세요.";

    private GeminiApiException(ExtractionErrorCode code, boolean permanent, Throwable cause) {
        super(USER_MESSAGE, code, permanent, cause);
    }

    public static GeminiApiException upstreamError(Throwable cause) {
        return new GeminiApiException(ExtractionErrorCode.LLM_UPSTREAM, false, cause);
    }

    public static GeminiApiException clientError(Throwable cause) {
        return new GeminiApiException(ExtractionErrorCode.LLM_INVALID_RESPONSE, true, cause);
    }

    /**
     * 4xx 는 원칙적으로 재시도해도 결과가 같은 확정 실패지만, 429·408 은 다시 보내면 풀릴 수 있어
     * 5xx 와 같은 일시 장애로 가른다.
     */
    public static GeminiApiException fromResponseError(RestClientResponseException e) {
        HttpStatusCode status = e.getStatusCode();
        boolean retryable =
            status.is5xxServerError()
                || status.value() == HttpStatus.TOO_MANY_REQUESTS.value()
                || status.value() == HttpStatus.REQUEST_TIMEOUT.value();
        return retryable ? upstreamError(e) : clientError(e);
    }

    /** 역직렬화 이전에 body 자체가 없는 경우 — transport·인프라 이슈일 가능성이 커 일시 실패로 본다. */
    public static GeminiApiException emptyResponse() {
        return new GeminiApiException(ExtractionErrorCode.LLM_UPSTREAM, false, null);
    }

    public static GeminiApiException parseError(Throwable cause) {
        return new GeminiApiException(ExtractionErrorCode.LLM_INVALID_RESPONSE, true, cause);
    }

    /** 스키마는 유효하지만 candidates/parts 가 비어 있는 경우 — safety filter 등 정책적 거부라 재시도가 무의미하다. */
    public static GeminiApiException noTextPart() {
        return new GeminiApiException(ExtractionErrorCode.LLM_INVALID_RESPONSE, true, null);
    }
}
