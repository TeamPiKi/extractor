package com.depromeet.piki.extractor.extraction.gemini;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.depromeet.piki.extractor.common.exception.ExtractionErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

// 계약은 permanent(일시 vs 확정)와 code(일시 LLM_UPSTREAM / 확정 LLM_INVALID_RESPONSE)로 표현된다 — 그에 맞춰 단언한다.
class GeminiApiExceptionTest {

    @ParameterizedTest
    @ValueSource(ints = {500, 502, 503, 504})
    @DisplayName("5xx 응답은 재시도 대상(permanent=false·LLM_UPSTREAM)으로 분류된다")
    void serverErrorsAreRetryable(int status) {
        HttpServerErrorException responseError = new HttpServerErrorException(HttpStatus.valueOf(status));

        GeminiApiException exception = GeminiApiException.fromResponseError(responseError);

        assertFalse(exception.permanent());
        assertEquals(ExtractionErrorCode.LLM_UPSTREAM, exception.code());
    }

    @Test
    @DisplayName("429 Too Many Requests 는 재시도 대상(permanent=false·LLM_UPSTREAM)으로 분류된다")
    void tooManyRequestsIsRetryable() {
        HttpClientErrorException responseError = new HttpClientErrorException(HttpStatus.TOO_MANY_REQUESTS);

        GeminiApiException exception = GeminiApiException.fromResponseError(responseError);

        assertFalse(exception.permanent());
        assertEquals(ExtractionErrorCode.LLM_UPSTREAM, exception.code());
    }

    @Test
    @DisplayName("408 Request Timeout 은 재시도 대상(permanent=false·LLM_UPSTREAM)으로 분류된다")
    void requestTimeoutIsRetryable() {
        HttpClientErrorException responseError = new HttpClientErrorException(HttpStatus.REQUEST_TIMEOUT);

        GeminiApiException exception = GeminiApiException.fromResponseError(responseError);

        assertFalse(exception.permanent());
        assertEquals(ExtractionErrorCode.LLM_UPSTREAM, exception.code());
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404})
    @DisplayName("429·408 을 제외한 4xx 응답은 확정 실패(permanent=true·LLM_INVALID_RESPONSE)로 분류된다")
    void otherClientErrorsArePermanent(int status) {
        HttpClientErrorException responseError = new HttpClientErrorException(HttpStatus.valueOf(status));

        GeminiApiException exception = GeminiApiException.fromResponseError(responseError);

        assertTrue(exception.permanent());
        assertEquals(ExtractionErrorCode.LLM_INVALID_RESPONSE, exception.code());
    }
}
