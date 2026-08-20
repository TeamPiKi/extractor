package com.depromeet.piki.extractor.extraction.http;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * HttpPageFetcher · PageFetchHttpClientConfig 가 쓰는 fetch 상수의 외부화 설정 — 기본값이 기본 동작을 정의하고,
 * yml 오버라이드로 튜닝 여지만 연다. application.yml 에 fetch 섹션이 없어도 모든 컴포넌트가
 * {@code @DefaultValue} 를 가져 constructor binding 으로 완전한 기본 인스턴스가 만들어진다.
 *
 * @param userAgent 기본 RestClient UA 는 일부 사이트에서 차단되므로 실제 브라우저 UA 로 위장한다.
 * @param connectionRequestTimeout 커넥션 풀에서 커넥션 획득까지의 최대 대기. 미설정 시 HttpClient5 기본이
 *     분 단위라, 풀 고갈·느린 upstream 에서 워커가 오래 붙잡히지 않게 짧게 명시한다(fail-fast).
 * @param maxRedirects 수동 redirect 추적 hop 상한. 단축·딥링크 체인이 실측 2 hop 까지 쓰므로 여유를 두되 무한·과도한
 *     체인은 여기서 끊는다. hop 마다 host 를 재검증(SSRF)하므로 상한을 늘려도 보안 부담이 커지지 않는다.
 * @param maxFetchBytes 응답 스트림에서 읽을 바이트 상한. 가지치기가 걷어내는 분량은 메모리에 남지 않지만,
 *     끝나지 않는 스트림은 read timeout 에도 안 걸리므로(데이터가 계속 도착한다) 읽기 자체를 여기서 끊는다.
 *     PageFetchHttpClientConfig 가 HttpClient5 의 기본 content decompression 을 끄지 않으므로 이 상한은
 *     <b>해제 후</b> 바이트에 걸린다 - 압축 폭탄이 노리는 지점이 바로 거기다.
 * @param maxRetainedChars 가지친 뒤에도 남는 분량의 상한(백스톱). 가지치기로 안 줄어드는 병리적 문서를 끊어
 *     동시 파싱 메모리(상한 x 동시 수)를 바운드한다. 정상 페이지는 여기 닿지 않는다.
 */
@ConfigurationProperties("fetch")
public record FetchProperties(
    @DefaultValue(DEFAULT_USER_AGENT) String userAgent,
    @DefaultValue("5s") Duration connectTimeout,
    @DefaultValue("15s") Duration readTimeout,
    @DefaultValue("2s") Duration connectionRequestTimeout,
    @DefaultValue("5") int maxRedirects,
    @DefaultValue("16777216") int maxFetchBytes,
    @DefaultValue("3000000") int maxRetainedChars
) {

    static final String DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36";

    /**
     * Spring 없이 도는 단위·E2E 테스트가 기본값으로 fetcher 를 조립할 수 있게 하는 편의 팩토리 —
     * 기본값 조립점을 한 곳으로 모은다.
     */
    public static FetchProperties defaults() {
        return new FetchProperties(
            DEFAULT_USER_AGENT,
            Duration.ofSeconds(5),
            Duration.ofSeconds(15),
            Duration.ofSeconds(2),
            5,
            16 * 1024 * 1024,
            3_000_000
        );
    }
}
