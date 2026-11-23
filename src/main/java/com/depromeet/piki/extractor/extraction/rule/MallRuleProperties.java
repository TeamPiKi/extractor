package com.depromeet.piki.extractor.extraction.rule;

import io.lettuce.core.RedisURI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 몰별 규칙 배포본을 읽는 설정. 운영 주입은 relaxed binding 환경변수({@code PRODUCT_EXTRACT_RULES_*})다.
 *
 * @param redisUrl 배포본이 놓인 Redis 주소({@code redis://사용자:비밀번호@host:port}). 빈값이면 읽지 않고 규칙 0개로
 *     동작한다. 비밀번호의 특수문자는 URL 인코딩해 적는다.
 * @param refreshInterval 배포본을 다시 읽는 주기. 규칙 변경의 반영 지연이 이 값이다.
 * @param timeout 연결과 명령 하나의 상한. 읽기는 요청 경로 밖이라 길 필요가 없다.
 */
@ConfigurationProperties(prefix = "product.extract.rules")
public record MallRuleProperties(
    @DefaultValue("") String redisUrl,
    @DefaultValue("60s") Duration refreshInterval,
    @DefaultValue("2s") Duration timeout
) {

    private static final String GUIDE =
        "product.extract.rules.redis-url(PRODUCT_EXTRACT_RULES_REDIS_URL)을 해석하지 못했다. "
            + "redis://사용자:비밀번호@host:port 형태여야 하고 비밀번호의 특수문자는 URL 인코딩한다";

    public MallRuleProperties {
        // 깨진 주소는 부팅에서 드러낸다 — 런타임이면 "Redis 에 못 닿음" 경고에 묻혀 규칙이 영영 0개인 채로 돈다
        if (!redisUrl.isBlank()) {
            requireValid(redisUrl);
        }
    }

    /**
     * 실패 메시지에 원인 예외를 싣지 않는다 — 파서의 예외 메시지에 주소 원문(비밀번호 포함)이 들어 있어 부팅 실패
     * 로그로 샌다. 인코딩 안 된 {@code #} 같은 문자는 예외 없이 비밀번호 조각을 host 로 밀어내므로 host 모양도 본다.
     */
    private static void requireValid(String redisUrl) {
        RedisURI parsed;
        try {
            parsed = RedisURI.create(redisUrl);
        } catch (RuntimeException e) {
            throw new IllegalStateException(GUIDE);
        }
        String host = parsed.getHost();
        if (host == null || host.isBlank() || host.contains(":") || host.contains("@")) {
            throw new IllegalStateException(GUIDE);
        }
    }

    public boolean enabled() {
        return !redisUrl.isBlank();
    }

    /** record 기본 구현은 주소를 그대로 내보낸다. */
    @Override
    public String toString() {
        return "MallRuleProperties[enabled=" + enabled() + ", refreshInterval=" + refreshInterval
            + ", timeout=" + timeout + "]";
    }
}
