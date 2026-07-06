package com.depromeet.piki.extractor.extraction.gemini;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GeminiRetryTest {

    // initial delay 를 0 으로 둬 Thread.sleep(0) 로 실제 대기 없이 재시도 횟수·분류만 검증한다.
    private GeminiRetry retryWith(int maxAttempts) {
        return new GeminiRetry(new GeminiProperties.Retry(maxAttempts, 0));
    }

    private final GeminiRetry retry = retryWith(3);

    @Test
    @DisplayName("첫 시도에 성공하면 재시도하지 않고 결과를 반환한다")
    void succeedsOnFirstTry() {
        AtomicInteger calls = new AtomicInteger();

        String result = retry.execute(() -> {
            calls.incrementAndGet();
            return "ok";
        });

        assertEquals("ok", result);
        assertEquals(1, calls.get());
    }

    @Test
    @DisplayName("재시도 대상 예외가 계속 나면 maxAttempts 만큼 시도 후 마지막 예외를 던진다")
    void retriesUpToMaxAttempts() {
        AtomicInteger calls = new AtomicInteger();

        assertThrows(GeminiApiException.class, () -> retry.execute(() -> {
            calls.incrementAndGet();
            throw GeminiApiException.emptyResponse();
        }));

        assertEquals(3, calls.get());
    }

    @Test
    @DisplayName("maxAttempts 가 1 이면 재시도하지 않고 첫 실패를 그대로 던진다")
    void noRetryWhenMaxAttemptsIsOne() {
        AtomicInteger calls = new AtomicInteger();

        assertThrows(GeminiApiException.class, () -> retryWith(1).execute(() -> {
            calls.incrementAndGet();
            throw GeminiApiException.emptyResponse();
        }));

        assertEquals(1, calls.get());
    }

    @Test
    @DisplayName("재시도 대상 예외 후 재시도에서 성공하면 그 결과를 반환한다")
    void recoversAfterRetry() {
        AtomicInteger calls = new AtomicInteger();

        String result = retry.execute(() -> {
            if (calls.incrementAndGet() < 2) {
                throw GeminiApiException.emptyResponse();
            }
            return "recovered";
        });

        assertEquals("recovered", result);
        assertEquals(2, calls.get());
    }

    @Test
    @DisplayName("확정 실패 예외는 재시도하지 않고 즉시 던진다")
    void permanentFailureIsNotRetried() {
        AtomicInteger calls = new AtomicInteger();

        assertThrows(GeminiApiException.class, () -> retry.execute(() -> {
            calls.incrementAndGet();
            throw GeminiApiException.parseError(new RuntimeException("깨진 JSON"));
        }));

        assertEquals(1, calls.get());
    }

    @Test
    @DisplayName("GeminiApiException 이 아닌 예외는 재시도하지 않고 즉시 던진다")
    void nonGeminiExceptionIsNotRetried() {
        AtomicInteger calls = new AtomicInteger();

        assertThrows(IllegalStateException.class, () -> retry.execute(() -> {
            calls.incrementAndGet();
            throw new IllegalStateException("boom");
        }));

        assertEquals(1, calls.get());
    }
}
