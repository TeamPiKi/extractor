package com.depromeet.piki.extractor.extraction.headless;

import com.depromeet.piki.extractor.common.exception.ExtractionException;
import com.depromeet.piki.extractor.domain.ProductLink;
import com.depromeet.piki.extractor.extraction.HeadlessExtractionProperties;
import com.depromeet.piki.extractor.extraction.PageContent;
import com.depromeet.piki.extractor.extraction.http.InternalHostGuard;
import com.depromeet.piki.extractor.extraction.http.RequestScopedDnsResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

// PIKI-HeadlessBrowser 의 POST /render 호출 + verdict 를 계약으로 번역하는 wire 구현.
// 렌더 서비스는 실제 브라우저(patchright)로 페이지를 열어 최선의 HTML 을 돌려준다 — 파싱은 여기서 하지 않고
// 렌더된 HTML 을 PageContent 로 되돌려 기존 파이프라인(구조화 → LLM)에 흘려넣는다.
//
// verdict 번역 규칙 — recall 최대화:
//   BLOCK              → HEADLESS_BLOCKED (일시 502). 렌더 서비스의 BLOCK 판정(HTTP 401/403/405/429/490 +
//                        챌린지 title)은 429·"잠시 후 다시" 같은 일시 신호를 포함해 영구/일시를 못 가른다 —
//                        fail-safe 원칙(분류 불가 실패는 일시)대로 일시로 두고, 결정론적 차단의 재시도 낭비는
//                        호출자 attempt 상한이 바운드한다.
//   그 외 + html 있음  → 진행. 렌더 서비스의 verdict(PASS/PARTIAL/EMPTY)는 자기 정규식 파서 기준의 title·price
//                        발견 여부일 뿐이라, EMPTY 여도 렌더된 DOM 에서 우리 파이프라인(구조화·LLM)이 뽑아낼 수
//                        있다 — verdict 로 HTML 을 버리면 그 recall 을 잃는다.
//   그 외 + html 없음  → HEADLESS_UPSTREAM (일시 502). 브라우저 오류(ERROR)·빈 렌더.
//
// SSRF: 브라우저 직행(headlessFirst) 경로는 plain fetch 를 타지 않아 그 안의 가드로 커버되지 않는다 —
// 렌더 서비스에 URL 을 넘기기 전에 같은 판정(InternalHostGuard)을 거쳐, 내부망·메타데이터 주소를 사설망의
// 실제 브라우저로 렌더시키는 구멍을 닫는다(에스컬레이션 경로에선 이중 검증 = 다층 방어).
@Component
public class HttpHeadlessRenderer implements HeadlessRenderer {

    private static final String RENDER_PATH = "/render";
    private static final String VERDICT_BLOCK = "BLOCK";

    private static final Logger log = LoggerFactory.getLogger(HttpHeadlessRenderer.class);

    private final RestClient restClient;
    private final HeadlessExtractionProperties properties;
    private final RequestScopedDnsResolver dnsResolver;
    private final InternalHostGuard internalHostGuard;

    public HttpHeadlessRenderer(
        @Qualifier(HeadlessRenderHttpClientConfig.HEADLESS_RENDER_REST_CLIENT) RestClient restClient,
        HeadlessExtractionProperties properties,
        RequestScopedDnsResolver dnsResolver
    ) {
        this.restClient = restClient;
        this.properties = properties;
        this.dnsResolver = dnsResolver;
        this.internalHostGuard = new InternalHostGuard(dnsResolver);
    }

    @Override
    public PageContent render(ProductLink link) {
        try {
            // 대상 URL 의 host 검증 — 여기서 걸리면 PageFetchException.blockedHost(422 BLOCKED_HOST)로,
            // plain 경로와 같은 계약 코드로 떨어진다.
            internalHostGuard.verify(link);
            return renderVerified(link);
        } finally {
            // 요청 스코프 DNS 캐시를 비워 다음 요청과 격리한다 (HttpPageFetcher.fetch 와 같은 규약).
            dnsResolver.clear();
        }
    }

    private PageContent renderVerified(ProductLink link) {
        HeadlessRenderResponse response = requestRender(link);
        response = requireNonNullResponse(response);

        String verdict = response.verdict();
        if (VERDICT_BLOCK.equals(verdict)) {
            log.warn("headless render verdict=BLOCK status={} url={}", response.status(), link.safeLogString());
            throw HeadlessRenderException.blocked();
        }

        String html = response.html();
        if (html == null || html.isBlank()) {
            // 브라우저 오류(ERROR)·빈 렌더 — error 원문은 대상 URL(쿼리스트링 포함)이 실릴 수 있어 마스킹해 남긴다.
            log.warn(
                "headless render no html verdict={} status={} error={} url={}",
                verdict,
                response.status(),
                maskUrls(response.error()),
                link.safeLogString()
            );
            throw HeadlessRenderException.upstream("verdict=" + verdict + " 인데 렌더 HTML 이 없다", null);
        }

        log.info(
            "headless render verdict={} source={} proxied={} status={} html={}chars url={}",
            verdict,
            response.source(),
            response.proxied(),
            response.status(),
            html.length(),
            link.safeLogString()
        );

        return new PageContent(link, capHtml(html), resolveFinalUrl(response.finalUrl(), link));
    }

    private HeadlessRenderResponse requestRender(ProductLink link) {
        try {
            return restClient.post()
                .uri(RENDER_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new HeadlessRenderRequest(link.value().toString(), true))
                .retrieve()
                .body(HeadlessRenderResponse.class);
        } catch (RestClientResponseException e) {
            // 렌더 서비스 자체의 비-2xx — 요청 격리 설계라 정상 흐름엔 없고, 서비스 장애·배포 중 신호다.
            throw HeadlessRenderException.upstream("render 서비스 응답 " + e.getStatusCode().value(), e);
        } catch (RestClientException e) {
            // 연결 실패·read 타임아웃 등 transport 오류.
            throw HeadlessRenderException.upstream("render 서비스 호출 실패", e);
        }
    }

    private HeadlessRenderResponse requireNonNullResponse(HeadlessRenderResponse response) {
        if (response == null) {
            throw HeadlessRenderException.upstream("render 응답 body 가 비어 있다", null);
        }
        return response;
    }

    // 렌더된 DOM 은 정적 fetch 보다 커질 수 있어 같은 안전 상한(파싱 비용·동시 메모리 바운드)을 적용한다.
    private String capHtml(String html) {
        int max = properties.maxHtmlChars();
        return html.length() > max ? html.substring(0, max) : html;
    }

    // 상대 URL resolve(Jsoup baseUri)용 최종 URL. 렌더 서비스가 redirect 를 따라갔으면 원본 link 와 다를 수 있다.
    // 값이 없거나 우리 형식(https 등)에 안 맞으면 원본 link 로 폴백한다 — baseUri 부정확은 치명이 아니고,
    // 여기서 INVALID_URL(422 확정) 을 새면 렌더는 성공했는데 실패로 종결되는 오판이 된다.
    private ProductLink resolveFinalUrl(String finalUrl, ProductLink link) {
        if (finalUrl == null || finalUrl.isBlank()) {
            return link;
        }
        try {
            return ProductLink.parse(finalUrl);
        } catch (ExtractionException e) {
            return link;
        }
    }

    // 렌더 서비스의 error 는 playwright 예외 원문(f"{type}: {e}")이라 대상 URL 전체(쿼리스트링의 토큰 포함)가
    // 실릴 수 있다 — 로그 규약(URL 마스킹)에 맞춰 URL 패턴을 지운 채 남긴다. (package-private: 단위 테스트 대상)
    static String maskUrls(String error) {
        if (error == null) {
            return null;
        }
        return error.replaceAll("https?://\\S+", "<url>");
    }
}
