package com.depromeet.piki.extractor.extraction.http;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.depromeet.piki.extractor.domain.ProductLink;
import com.depromeet.piki.extractor.extraction.PageContent;
import io.micrometer.observation.ObservationRegistry;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.web.client.RestClient;

@Disabled("실제 외부 페이지 fetch (지그재그 same-domain · 무신사 OneLink cross-domain). 검증 필요 시 수동 enable.")
class HttpPageFetcherRedirectE2ETest {

    private final RequestScopedDnsResolver dnsResolver = new RequestScopedDnsResolver();
    private final RequestScopedCookieStore cookieStore = new RequestScopedCookieStore();
    private final HttpPageFetcher fetcher =
        new HttpPageFetcher(
            new PageFetchHttpClientConfig()
                .pageFetchRestClient(ObservationRegistry.NOOP, dnsResolver, cookieStore, FetchProperties.defaults()),
            dnsResolver,
            cookieStore,
            FetchProperties.defaults());

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("www 지그재그 URL 은 same-domain redirect 를 따라가 본문을 받는다")
    void zigzagWwwFollowsSameDomainRedirect() {
        // 회귀: 보강 전엔 www.zigzag.kr 의 301 을 따라가지 못해 emptyBody 로 실패했다.
        ProductLink link = ProductLink.parse("https://www.zigzag.kr/catalog/products/136677613");

        PageContent page = fetcher.fetch(link);

        assertTrue(page.retainedChars() > 1_000, "redirect 를 따라가 실제 본문을 받았어야 한다");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("무신사 OneLink 단축링크는 cross-domain redirect 를 따라가 최종 상품 페이지 본문을 받는다")
    void musinsaOneLinkFollowsCrossDomainRedirect() {
        // musinsa.onelink.me(AppsFlyer) 가 301 로 www.musinsa.com/products/... 로 보낸다(등록도메인 변경).
        // PC UA 로 요청하는 우리 fetch 는 이 301 경로를 받는다. cross-domain 허용 후 따라가 최종 무신사 페이지에 도달해야 한다.
        ProductLink link = ProductLink.parse("https://musinsa.onelink.me/PvkC/lx16ha0g");

        PageContent page = fetcher.fetch(link);

        // endsWith("musinsa.com") 만으로는 evilmusinsa.com 같은 호스트도 통과해 회귀 검출이 약하므로 정확 매칭한다.
        String host = page.finalUrl().value().getHost();
        if (host == null) {
            host = "";
        }
        assertTrue(host.equals("musinsa.com") || host.endsWith(".musinsa.com"), "최종 호스트가 musinsa.com 계열이어야 한다");
        assertNotNull(
            page.document().selectFirst("meta[property=og:title]"),
            "최종 무신사 상품 페이지(OG 메타태그)를 받았어야 한다"
        );
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("쿠키를 심고 자기 자신으로 302 하는 딥링크 바운스를 쿠키를 들고 빠져나온다")
    void deepLinkCookieBounceResolves() {
        // al.wconcept.co.kr 은 Sec-CH-UA 를 받으면 쿠키를 심는 자기 리다이렉트를 한 번 태운다(2026-09-12 실측).
        // 쿠키를 안 들고 있으면 같은 자리를 계속 돌다 redirect 상한을 소진한다. 운영 UA 는 아직 이 헤더를 보내지
        // 않으므로 여기서만 얹어 그 바운스를 일부러 태운다.
        RequestScopedDnsResolver resolver = new RequestScopedDnsResolver();
        RequestScopedCookieStore cookies = new RequestScopedCookieStore();
        RestClient clientHints = new PageFetchHttpClientConfig()
            .pageFetchRestClient(ObservationRegistry.NOOP, resolver, cookies, FetchProperties.defaults())
            .mutate()
            .defaultHeader("Sec-CH-UA", "\"Chromium\";v=\"153\", \"Google Chrome\";v=\"153\", \"Piki\";v=\"1\"")
            .build();
        HttpPageFetcher bouncing = new HttpPageFetcher(clientHints, resolver, cookies, FetchProperties.defaults());

        PageContent page = bouncing.fetch(ProductLink.parse("https://al.wconcept.co.kr/8xpg78c"));

        String host = page.finalUrl().value().getHost();
        assertTrue(host != null && host.endsWith("wconcept.co.kr"), "최종 호스트가 W컨셉이어야 한다");
        assertTrue(page.retainedChars() > 1_000, "바운스를 빠져나와 상품 페이지 본문을 받았어야 한다");
    }
}
