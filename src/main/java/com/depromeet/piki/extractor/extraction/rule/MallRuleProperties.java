package com.depromeet.piki.extractor.extraction.rule;

import java.time.Duration;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 몰별 규칙 배포본을 읽는 설정. 운영 주입은 relaxed binding 환경변수({@code PRODUCT_EXTRACT_RULES_REDIS_*})다.
 *
 * <p>주소와 비밀번호를 URL 하나로 받지 않는 이유는 둘의 출처가 달라서다. 주소는 배포가 서버 Name 태그로 매번
 * 계산하고 비밀번호는 SSM 에 있다. URL 로 합치면 비밀번호의 특수문자를 인코딩해야 하고, 해석 실패 메시지에 주소
 * 원문(비밀번호 포함)이 실린다.
 *
 * @param redisAddress 배포본이 놓인 Redis 의 {@code host:port}. 내부 주소라 코드·yml 에 박지 않는다.
 * @param redisUsername 규칙 키만 읽는 계정. 선언의 정본은 TeamPiKi/core 의 infra/compose/db.yml.
 * @param redisPassword 빈값이면 읽지 않고 규칙 0개로 동작한다 — 주소는 배포가 늘 넣으므로 이 값이 켜는 스위치다.
 * @param refreshInterval 배포본을 다시 읽는 주기. 규칙 변경의 반영 지연이 이 값이다.
 * @param timeout 연결과 명령 하나의 상한. 읽기는 요청 경로 밖이라 길 필요가 없다.
 */
@ConfigurationProperties(prefix = "product.extract.rules")
public record MallRuleProperties(
    @DefaultValue("") String redisAddress,
    @DefaultValue("extraction-rules-reader") String redisUsername,
    @DefaultValue("") String redisPassword,
    @DefaultValue("60s") Duration refreshInterval,
    @DefaultValue("2s") Duration timeout
) {

    private static final Pattern ADDRESS = Pattern.compile("[A-Za-z0-9.-]+:\\d{1,5}");

    public MallRuleProperties {
        // 깨진 주소는 부팅에서 드러낸다 — 런타임이면 "Redis 에 못 닿음" 경고에 묻혀 규칙이 영영 0개인 채로 돈다
        if (!redisAddress.isBlank() && !ADDRESS.matcher(redisAddress).matches()) {
            throw new IllegalStateException(
                "product.extract.rules.redis-address(PRODUCT_EXTRACT_RULES_REDIS_ADDRESS)는 host:port 형태여야 한다. "
                    + "현재 값: [" + redisAddress + "]");
        }
    }

    public boolean enabled() {
        return !redisAddress.isBlank() && !redisPassword.isBlank();
    }

    String redisHost() {
        return redisAddress.substring(0, redisAddress.lastIndexOf(':'));
    }

    int redisPort() {
        return Integer.parseInt(redisAddress.substring(redisAddress.lastIndexOf(':') + 1));
    }

    /** record 기본 구현은 비밀번호를 그대로 내보낸다. */
    @Override
    public String toString() {
        return "MallRuleProperties[redisAddress=" + redisAddress + ", redisUsername=" + redisUsername
            + ", enabled=" + enabled() + ", refreshInterval=" + refreshInterval + ", timeout=" + timeout + "]";
    }
}
