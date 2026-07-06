package com.depromeet.piki.extractor.extraction.gemini;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// PIKI-Server: product/service/gemini/GeminiRetry.kt 포팅.
//
// Gemini 호출의 일시 장애를 지수 백오프로 재시도한다.
//
// 재시도 대상(permanent=false)인 GeminiApiException(5xx · 429 · 408 · 네트워크 타임아웃 · 빈 응답)만 재시도하고,
// 확정 실패(permanent=true) 등 재시도해도 의미 없는 실패는 즉시 전파한다. 재시도 여부 판단을 예외 타입이 아니라
// GeminiApiException 의 일시/확정 구분에 위임하므로 분류가 한 곳(GeminiApiException)에 모인다.
// (원본은 ErrorCategory.RETRYABLE 로 판정 — 번역 규칙: RETRYABLE ⟷ permanent=false.)
//
// 재시도 횟수·백오프는 GeminiProperties.Retry 로 외부 주입한다 — maxAttempts 가 곧 billed API 호출 상한이라
// 운영에서 비용·quota 에 맞춰 조정할 수 있어야 하기 때문이다.
public class GeminiRetry {

    // 2^5 = 32. initialDelayMs=1000 기본일 때 최대 base 약 32 초.
    private static final int MAX_SHIFT = 5;

    private static final Logger log = LoggerFactory.getLogger(GeminiRetry.class);

    private final GeminiProperties.Retry config;

    public GeminiRetry(GeminiProperties.Retry config) {
        this.config = config;
    }

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

    // 지수 백오프 + full jitter: [0, initial * 2^shift] 범위의 난수.
    // jitter 는 동시에 실패한 다수 요청이 같은 시점에 재시도하며 몰리는 thundering herd 를 막는다.
    //
    // shift 는 MAX_SHIFT 로 제한 — 두 가지 목적의 안전망:
    //   1. shl 결과가 Long 부호 비트를 넘어 음수가 되면 nextLong 이 IllegalArgumentException 으로 깨진다.
    //      운영자가 max-attempts 를 비현실적으로 크게 설정해도 산술적으로 안전.
    //   2. 깊은 attempts 에서 base 가 분/시간 단위로 폭주하는 것을 방지.
    private long backoffMillis(int attempt) {
        int shift = Math.min(attempt - 1, MAX_SHIFT);
        long exponential = config.initialDelayMs() << shift;
        return ThreadLocalRandom.current().nextLong(exponential + 1);
    }

    // Kotlin Thread.sleep 은 InterruptedException 을 던지지 않았으나 Java 는 checked 라 여기서 처리한다.
    // 인터럽트는 재시도 대기 중의 비정상 종료 신호라 interrupt 플래그를 복원하고 불변식 위반(500)으로 올린다.
    private void sleep(long delayMs) {
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Gemini 재시도 대기 중 인터럽트됨", e);
        }
    }
}
