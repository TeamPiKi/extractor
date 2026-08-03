package com.depromeet.piki.extractor.extraction;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 헤드리스(차단 우회) 추출 설정 — renderer(구 PIKI-HeadlessBrowser)의 {@code POST /render} 호출 파라미터.
 * 운영 주입은 relaxed binding 환경변수({@code PRODUCT_EXTRACT_HEADLESS_*})다.
 *
 * @param baseUrl 렌더 서비스 주소(사설망, 예: {@code http://<headless-private-ip>:8000}). 내부 주소라 코드·yml 에
 *     박지 않고 env 로만 주입한다.
 * @param readTimeout 렌더 서비스 내부 최악(goto + 렌더 정착 대기)을 다 기다리지는 않는다 — 호출자 read 예산 안에
 *     LLM fallback 몫을 남겨야 하기 때문이다. 층별 예산의 정본은 docs/api-contract.md §3. 상한을 넘긴 렌더는 일시
 *     실패로 떨어져 호출자 재시도가 진다.
 * @param compress 응답 zstd 압축 전송 요청(서버간 전송량 절감). 해제는 응답 헤더({@code X-Encoding}) 기준이라
 *     compress 필드를 모르는 구버전 renderer(무시하고 plain JSON 으로 답한다)와도 호환된다 — 켜 둔 채로 배포
 *     순서와 무관하게 안전하고, 이 스위치는 압축 경로에 문제가 생겼을 때 끄는 kill-switch 다.
 * @param zstdDictDir zstd 학습 사전 디렉토리(파일명 = 사전ID, renderer {@code compress.py} 의 DICT_ID 규약).
 *     빈값이면 사전 없음. 롤아웃 순서: 사전 파일을 여기 먼저 배포한 뒤 renderer 쪽 사전 경로를 켠다 — 순서가
 *     뒤집히면 미보유 사전ID 를 받아 해제 불가(일시 실패)가 된다.
 */
@ConfigurationProperties(prefix = "product.extract.headless")
public record HeadlessExtractionProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("") String baseUrl,
    @DefaultValue("2s") Duration connectTimeout,
    @DefaultValue("20s") Duration readTimeout,
    @DefaultValue("3000000") int maxHtmlChars,
    @DefaultValue("true") boolean compress,
    @DefaultValue("") String zstdDictDir
) {

    public HeadlessExtractionProperties {
        // fail-fast: 스위치는 켰는데 주소가 없거나 깨진 반쪽 구성은 부팅에서 즉시 드러낸다 — 런타임 첫 호출에서
        // 터지면 일시 실패로 위장돼 오설정이 호출자 재시도 뒤에 숨는다. blank 만 잡으면 스킴 누락
        // ("headless.internal:8000" — RFC 상 scheme=headless.internal 로 유효 파싱된다) 같은 오설정이 그 시나리오를
        // 그대로 재현하므로, http/https 스킴 + host 존재까지 검증한다.
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

    /** 테스트 편의 조립기 — 스위치만 받고 나머지는 기본값으로 채운다 ({@code FetchProperties.defaults} 와 같은 결). */
    public static HeadlessExtractionProperties of(boolean enabled) {
        return new HeadlessExtractionProperties(
            enabled,
            enabled ? "http://headless.test:8000" : "",
            Duration.ofSeconds(2),
            Duration.ofSeconds(20),
            3_000_000,
            true,
            ""
        );
    }
}
