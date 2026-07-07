package com.depromeet.piki.extractor.extraction;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

// 헤드리스(차단 우회) 추출 설정 — PIKI-HeadlessBrowser 의 POST /render 호출 파라미터.
// 운영 주입은 relaxed binding 환경변수: PRODUCT_EXTRACT_HEADLESS_ENABLED · PRODUCT_EXTRACT_HEADLESS_BASE_URL.
//
//   readTimeout — 실측(kream 프록시 5.5s·oliveyoung 1.6s·ably 1.9s) 대비 약 4배 여유. 렌더 서비스 내부
//                 최악(goto 40s + 가격 대기 8s)을 다 기다리지 않는 이유: 호출자(PIKI-Server) read 55s 예산 안에서
//                 LLM fallback(30s) 몫을 남겨야 한다(docs/api-contract.md §3). 상한 초과 렌더는 일시 실패로
//                 떨어져 outbox recover 가 재시도한다.
//   maxHtmlChars — 렌더된 DOM 보관·파싱 비용의 안전 상한 (정적 fetch 의 FetchProperties.maxFetchChars 와 같은 값).
@ConfigurationProperties(prefix = "product.extract.headless")
public record HeadlessExtractionProperties(
    // 헤드리스 스위치. 기본 false — 끄면 headlessFirst 힌트·에스컬레이션 모두 무시되고 전량 plain 위임(zero-diff).
    @DefaultValue("false") boolean enabled,
    // 렌더 서비스 주소 (사설망, 예: http://<headless-private-ip>:8000). 내부 주소라 코드·yml 에 박지 않고 env 로만 주입.
    @DefaultValue("") String baseUrl,
    @DefaultValue("2s") Duration connectTimeout,
    @DefaultValue("20s") Duration readTimeout,
    @DefaultValue("3000000") int maxHtmlChars
) {

    public HeadlessExtractionProperties {
        // fail-fast: 스위치는 켰는데 주소가 없거나 깨진 반쪽 구성은 부팅에서 즉시 드러낸다 — 런타임 첫 호출에서
        // 터지면 일시 실패(HEADLESS_UPSTREAM)로 위장돼 오설정이 recover 재시도 뒤에 숨는다. blank 만 잡으면
        // 스킴 누락("headless.internal:8000" — RFC 상 scheme=headless.internal 로 유효 파싱됨) 같은 오설정이
        // 그 시나리오를 그대로 재현하므로, http/https 스킴 + host 존재까지 검증한다.
        if (enabled) {
            requireValidBaseUrl(baseUrl);
        }
    }

    private static void requireValidBaseUrl(String baseUrl) {
        String guide =
            "product.extract.headless.enabled=true 이면 base-url(PRODUCT_EXTRACT_HEADLESS_BASE_URL)이 "
                + "http(s)://host[:port] 형태여야 한다. 현재 값: [" + baseUrl + "]";
        if (baseUrl.isBlank()) {
            throw new IllegalStateException(guide);
        }
        URI uri;
        try {
            uri = new URI(baseUrl);
        } catch (URISyntaxException e) {
            throw new IllegalStateException(guide, e);
        }
        boolean httpScheme = "http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme());
        if (!httpScheme || uri.getHost() == null) {
            throw new IllegalStateException(guide);
        }
    }

    // 테스트 편의: 스위치 외 나머지는 기본값으로 조립한다 (FetchProperties.defaults 와 같은 결).
    public static HeadlessExtractionProperties of(boolean enabled) {
        return new HeadlessExtractionProperties(
            enabled,
            enabled ? "http://headless.test:8000" : "",
            Duration.ofSeconds(2),
            Duration.ofSeconds(20),
            3_000_000
        );
    }
}
