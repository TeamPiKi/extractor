package com.depromeet.piki.extractor.extraction.http;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

// HttpPageFetcher · PageFetchHttpClientConfig 가 쓰는 fetch 상수의 외부화 설정 — 기본값이 기본 동작을 정의하고,
// yml 오버라이드로 튜닝 여지만 연다. application.yml 에 fetch 섹션이 없어도
// 모든 컴포넌트가 @DefaultValue 를 가져 constructor binding 으로 완전한 기본 인스턴스가 만들어진다.
//   userAgent                — 기본 RestClient UA 는 일부 사이트에서 차단되므로 실제 브라우저 UA 로 위장.
//   connectTimeout           — TCP 연결 수립 상한.
//   readTimeout              — 응답(read) 상한.
//   connectionRequestTimeout — 커넥션 풀에서 커넥션 획득까지의 최대 대기. 미설정 시 HttpClient5 기본 3분이라 짧게 명시(fail-fast).
//   maxRedirects             — 수동 redirect 추적 hop 상한. www↔non-www·http→https 정도라 한두 번이면 충분, 무한·체인은 여기서 끊는다.
//   maxFetchChars            — fetch 본문 보관·파싱 비용을 막는 안전 상한(LLM 토큰 상한 아님). 구조화 추출이 페이지 전체를
//                              보게 넉넉히 두되, 동시 파싱 메모리(상한 x 동시 수)를 감안해 무제한이 아니라 3MB 로 바운드.
@ConfigurationProperties("fetch")
public record FetchProperties(
    @DefaultValue(DEFAULT_USER_AGENT) String userAgent,
    @DefaultValue("5s") Duration connectTimeout,
    @DefaultValue("15s") Duration readTimeout,
    @DefaultValue("2s") Duration connectionRequestTimeout,
    @DefaultValue("3") int maxRedirects,
    @DefaultValue("3000000") int maxFetchChars
) {

    static final String DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36";

    // Spring 없이 도는 단위·E2E 테스트가 기본값으로 fetcher 를 조립할 수 있게 하는 편의 팩토리 —
    // 기본값 조립점을 한 곳으로 모은다.
    public static FetchProperties defaults() {
        return new FetchProperties(
            DEFAULT_USER_AGENT,
            Duration.ofSeconds(5),
            Duration.ofSeconds(15),
            Duration.ofSeconds(2),
            3,
            3_000_000
        );
    }
}
