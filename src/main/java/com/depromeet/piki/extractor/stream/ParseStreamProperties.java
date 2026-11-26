package com.depromeet.piki.extractor.stream;

import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 파싱 요청 스트림을 읽는 설정. 운영 주입은 relaxed binding 환경변수({@code PRODUCT_EXTRACT_STREAM_*})다.
 *
 * @param sources 요청을 읽을 Redis. 서버 한 대가 모든 환경을 받으므로 환경마다 하나씩 적는다
 *     ({@code PRODUCT_EXTRACT_STREAM_SOURCES_0_NAME}·{@code ..._0_REDIS_ADDRESS}·{@code ..._0_REDIS_PASSWORD}).
 *     주소나 비밀번호가 빈 환경은 읽지 않는다.
 * @param concurrency Redis 하나에서 동시에 처리하는 요청 수.
 * @param readBlock 새 요청을 기다리는 한 번의 대기. 종료가 이 시간만큼 늦어진다.
 * @param commandTimeout 연결과 명령 하나의 상한. 대기하는 읽기도 이 상한을 타므로 {@code readBlock} 보다 커야 한다.
 * @param reclaimInterval 확인 응답 없이 남은 요청을 찾는 주기.
 * @param policyRefreshInterval 정책 배포본을 다시 읽는 주기. 정책 변경의 반영 지연이 이 값이다.
 * @param retryDelay 읽기가 실패한 뒤 다시 읽기까지 쉬는 시간. 실패가 이어지면 {@code maxRetryDelay} 까지 두 배씩 는다.
 * @param maxRetryDelay Redis 가 죽어 있는 동안 다시 읽는 간격의 상한. 실패 로그의 빈도가 이 값으로 묶인다.
 * @param stopTimeout 종료 때 돌던 추출이 결과를 쓰기를 기다리는 상한. 배포의 종료 유예보다 작아야 한다.
 * @param staleConsumerAfter 대기 목록이 비고 이 시간 넘게 읽지 않은 소비자 이름을 그룹에서 지운다.
 */
@ConfigurationProperties(prefix = "product.extract.stream")
public record ParseStreamProperties(
    @DefaultValue List<Source> sources,
    @DefaultValue("4") int concurrency,
    @DefaultValue("2s") Duration readBlock,
    @DefaultValue("5s") Duration commandTimeout,
    @DefaultValue("15s") Duration reclaimInterval,
    @DefaultValue("60s") Duration policyRefreshInterval,
    @DefaultValue("1s") Duration retryDelay,
    @DefaultValue("30s") Duration maxRetryDelay,
    @DefaultValue("20s") Duration stopTimeout,
    @DefaultValue("1h") Duration staleConsumerAfter
) {

    public ParseStreamProperties {
        sources = List.copyOf(sources);
        if (concurrency < 1) {
            throw new IllegalStateException("product.extract.stream.concurrency 는 1 이상이어야 한다");
        }
        if (commandTimeout.compareTo(readBlock) <= 0) {
            throw new IllegalStateException("product.extract.stream.command-timeout 은 read-block 보다 커야 한다");
        }
        if (sources.stream().map(Source::name).distinct().count() != sources.size()) {
            throw new IllegalStateException("product.extract.stream.sources 의 name 이 겹친다");
        }
    }

    /**
     * 주소와 비밀번호를 URL 하나로 받지 않는 이유는 MallRuleProperties 와 같다.
     *
     * @param name 로그와 스레드 이름에 쓰는 환경 이름.
     * @param redisAddress 그 환경 Redis 의 {@code host:port}. 배포가 서버 Name 태그로 매번 계산한다.
     * @param redisUsername 스트림과 정책 키에만 닿는 계정. 선언의 정본은 TeamPiKi/core 의 infra/compose/db.yml.
     * @param redisPassword 빈값이면 이 환경의 스트림을 읽지 않는다 — 주소는 배포가 늘 넣으므로 이 값이 켜는 스위치다.
     */
    public record Source(
        String name,
        @DefaultValue("") String redisAddress,
        @DefaultValue("extraction-stream-worker") String redisUsername,
        @DefaultValue("") String redisPassword
    ) {

        private static final Pattern ADDRESS = Pattern.compile("[A-Za-z0-9.-]+:\\d{1,5}");

        /** 깨진 주소는 부팅에서 드러낸다 — 런타임이면 "Redis 에 못 닿음" 경고에 묻혀 그 환경의 요청이 영영 안 읽힌다. */
        public Source {
            if (name == null || name.isBlank()) {
                throw new IllegalStateException("product.extract.stream.sources 의 name 이 비었다");
            }
            if (!redisAddress.isBlank() && !ADDRESS.matcher(redisAddress).matches()) {
                throw new IllegalStateException(
                    "product.extract.stream.sources 의 redis-address 는 host:port 형태여야 한다. 현재 값: [" + redisAddress + "]");
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
            return "Source[name=" + name + ", redisAddress=" + redisAddress + ", enabled=" + enabled() + "]";
        }
    }
}
