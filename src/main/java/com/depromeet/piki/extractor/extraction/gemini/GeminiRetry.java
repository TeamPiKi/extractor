package com.depromeet.piki.extractor.extraction.gemini;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Gemini 호출의 일시 장애를 지수 백오프로 재시도한다. 재시도 여부 판단을 개별 예외 타입이 아니라
 * GeminiApiException 의 일시/확정 구분에 위임하므로, 분류는 GeminiApiException 한 곳에만 있다.
 */
@Slf4j
@RequiredArgsConstructor
public class GeminiRetry {

    private static final int MAX_SHIFT = 5;

    private final GeminiProperties.Retry config;

    public <T> T execute(Supplier<T> block) {
        int attempt = 1;
        while (true) {
            try {
                return block.get();
            } catch (GeminiApiException e) {
                if (e.permanent() || attempt >= config.maxAttempts()) {
                    throw e;
                }
                long delayMs = backoffMillis(attempt);
                log.warn(
                    "Gemini 호출 재시도 {}/{} — {}ms 후 ({})",
                    attempt,
                    config.maxAttempts() - 1,
                    delayMs,
                    e.getMessage()
                );
                sleep(delayMs);
                attempt++;
            }
        }
    }

    /**
     * 지수 백오프 + full jitter. jitter 는 동시에 실패한 다수 요청이 같은 시점에 재시도하며 몰리는
     * thundering herd 를 막는다.
     *
     * <p>shift 를 MAX_SHIFT 로 제한하는 것은 두 가지 안전망이다:
     * <ol>
     *   <li>{@code <<} 결과가 long 부호 비트를 넘어 음수가 되면 nextLong 이 IllegalArgumentException 으로
     *       깨진다 — 운영자가 max-attempts 를 비현실적으로 크게 잡아도 산술적으로 안전하게 둔다.</li>
     *   <li>깊은 attempt 에서 대기가 분·시간 단위로 폭주하는 것을 막는다.</li>
     * </ol>
     */
    private long backoffMillis(int attempt) {
        int shift = Math.min(attempt - 1, MAX_SHIFT);
        long exponential = config.initialDelayMs() << shift;
        return ThreadLocalRandom.current().nextLong(exponential + 1);
    }

    /** 인터럽트는 재시도 대기 중의 비정상 종료 신호라, 플래그를 복원하고 불변식 위반(500)으로 올린다. */
    private void sleep(long delayMs) {
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Gemini 재시도 대기 중 인터럽트됨", e);
        }
    }
}
