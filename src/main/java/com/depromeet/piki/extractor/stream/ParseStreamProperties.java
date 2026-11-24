package com.depromeet.piki.extractor.stream;

import io.lettuce.core.RedisURI;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 파싱 요청 스트림을 읽는 설정. 운영 주입은 relaxed binding 환경변수({@code PRODUCT_EXTRACT_STREAM_*})다.
 *
 * @param sources 요청을 읽을 Redis. 서버 한 대가 모든 환경을 받으므로 환경마다 하나씩 적는다
 *     ({@code PRODUCT_EXTRACT_STREAM_SOURCES_0_NAME}·{@code ..._0_REDIS_URL}). 비었으면 스트림을 읽지 않는다.
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
     * @param name 로그와 스레드 이름에 쓰는 환경 이름.
     * @param redisUrl {@code redis://사용자:비밀번호@host:port}. 비밀번호의 특수문자는 URL 인코딩해 적는다.
     */
    public record Source(String name, String redisUrl) {

        private static final String GUIDE =
            "product.extract.stream.sources 의 redis-url 을 해석하지 못했다. "
                + "redis://사용자:비밀번호@host:port 형태여야 하고 비밀번호의 특수문자는 URL 인코딩한다";

        /** 깨진 주소는 부팅에서 드러낸다. 원인 예외를 싣지 않는 이유는 MallRuleProperties 와 같다. */
        public Source {
            if (name == null || name.isBlank()) {
                throw new IllegalStateException("product.extract.stream.sources 의 name 이 비었다");
            }
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

        /** record 기본 구현은 주소를 그대로 내보낸다. */
        @Override
        public String toString() {
            return "Source[name=" + name + "]";
        }
    }
}
