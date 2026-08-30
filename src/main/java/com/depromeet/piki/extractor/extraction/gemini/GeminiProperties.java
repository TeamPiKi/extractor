package com.depromeet.piki.extractor.extraction.gemini;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "gemini")
public record GeminiProperties(
    String apiKey,
    String model,
    Retry retry
) {

    /**
     * 기본 모델의 단일 진실 원천(single source of truth).
     *
     * <p>application.yml 등 다른 곳에 모델 리터럴을 중복 정의하지 않는다 — 여러 곳에 흩어지면 한쪽만 바뀌어
     * "다른 모델이 기본값이 되는" drift 가 생긴다. 운영에서 모델을 바꾸려면 GEMINI_MODEL 환경변수로 override 한다
     * (relaxed binding: {@code GEMINI_MODEL -> gemini.model}).
     *
     * <p>preview 모델은 2 주 사전공지 후 deprecate 정책이라 운영 안정성을 위해 GA 모델만 기본값으로 둔다.
     */
    public static final String DEFAULT_MODEL = "gemini-3.1-flash-lite";

    /**
     * model 기본값이 클래스 상수(DEFAULT_MODEL)라 {@code @DefaultValue} 대신 이 생성자에서 채운다.
     * 생성자가 둘(canonical + 편의)이라 Spring 바인딩 대상을 {@code @ConstructorBinding} 으로 명시한다.
     */
    @ConstructorBinding
    public GeminiProperties {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("GEMINI_API_KEY 가 비어 있습니다.");
        }
        if (model == null) {
            model = DEFAULT_MODEL;
        }
        if (model.isBlank()) {
            throw new IllegalArgumentException("gemini.model 이 비어 있습니다.");
        }
        if (retry == null) {
            retry = new Retry();
        }
    }

    /** 테스트·직접 생성용 편의 생성자. */
    public GeminiProperties(String apiKey) {
        this(apiKey, DEFAULT_MODEL, new Retry());
    }

    /** record 기본 toString 은 apiKey 를 그대로 노출하므로, 로그 유출 방지를 위해 마스킹한다. */
    @Override
    public String toString() {
        return "GeminiProperties(apiKey=*secret*, model=" + model + ", retry=" + retry + ")";
    }

    /**
     * Gemini 호출 재시도 파라미터. maxAttempts 는 곧 한 요청이 유발할 수 있는
     * billed API 호출 횟수 상한이므로, API 비용·quota 를 고려해 운영에서 직접 조정한다.
     *
     * <p>{@code @DefaultValue} 는 부분 바인딩(예: max-attempts 만 지정)에서도 나머지 필드의 기본값을 유지시킨다
     * — 아래 no-arg 생성자와 값이 어긋나면 바인딩 경로와 직접 생성 경로의 기본값이 갈린다.
     *
     * @param maxAttempts 총 시도 횟수(초기 호출 + 재시도). URL 파싱 재시도를 호출자(PIKI-Server) outbox recover 로
     *     일원화했으므로 기본값은 Gemini 내부 재시도를 끄는 쪽이다.
     */
    public record Retry(
        @DefaultValue("1") int maxAttempts,
        @DefaultValue("1000") long initialDelayMs
    ) {

        /** 생성자가 둘(canonical + no-arg)이라 바인딩 대상을 명시한다. */
        @ConstructorBinding
        public Retry {
            if (maxAttempts < 1) {
                throw new IllegalArgumentException("gemini.retry.max-attempts 는 1 이상이어야 합니다: " + maxAttempts);
            }
            if (initialDelayMs < 0) {
                throw new IllegalArgumentException("gemini.retry.initial-delay-ms 는 0 이상이어야 합니다: " + initialDelayMs);
            }
        }

        public Retry() {
            this(1, 1000);
        }
    }
}
