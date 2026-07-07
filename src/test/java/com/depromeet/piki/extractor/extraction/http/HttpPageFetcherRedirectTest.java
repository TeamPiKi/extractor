package com.depromeet.piki.extractor.extraction.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.depromeet.piki.extractor.common.exception.ExtractionErrorCode;
import com.depromeet.piki.extractor.domain.ProductLink;
import com.depromeet.piki.extractor.extraction.PageContent;
import java.net.InetAddress;
import java.net.URI;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

// redirect 루프(3xx 따라가기·cross-domain 차단·다운그레이드 차단·hop 상한·비-redirect 3xx)를 네트워크 없이 검증한다.
// 응답은 MockRestServiceServer 로 제어하고, DNS 는 가짜 공인 IP 로 주입해 SSRF 가드를 통과시켜 redirect 로직만 격리한다.
//
// PageFetchException 은 code()/permanent()/escalatable() 로 계약을 표현하므로 각 실패 갈래를 code() 로 고정한다.
class HttpPageFetcherRedirectTest {

    // 모든 host 를 공인 IP 로 해석해 가드를 통과시킨다(여기선 redirect 따라가기·도메인 차단 로직만 검증).
    private final RequestScopedDnsResolver.HostResolver publicIp =
        host -> new InetAddress[] {InetAddress.getByName("93.184.216.34")};

    private HttpPageFetcher fetcherWith(Consumer<MockRestServiceServer> configure) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        configure.accept(server);
        return new HttpPageFetcher(builder.build(), new RequestScopedDnsResolver(publicIp), FetchProperties.defaults());
    }

    @Test
    @DisplayName("same-domain redirect 를 따라가 최종 페이지 본문을 받는다")
    void followsSameDomainRedirect() {
        HttpPageFetcher fetcher =
            fetcherWith(server -> {
                server.expect(requestTo("https://www.zigzag.kr/p"))
                    .andRespond(withStatus(HttpStatus.MOVED_PERMANENTLY).location(URI.create("https://zigzag.kr/p")));
                server.expect(requestTo("https://zigzag.kr/p"))
                    .andRespond(withSuccess("<html>product</html>", MediaType.TEXT_HTML));
            });

        PageContent page = fetcher.fetch(ProductLink.parse("https://www.zigzag.kr/p"));

        assertEquals("<html>product</html>", page.html());
        // link 는 사용자 등록 원본, finalUrl 은 redirect 를 따라간 최종 URL(baseUri 용으로 구분).
        assertEquals("https://www.zigzag.kr/p", page.link().value().toString());
        assertEquals("https://zigzag.kr/p", page.finalUrl().value().toString());
    }

    @Test
    @DisplayName("cross-domain redirect 도 따라가 최종 페이지 본문을 받는다")
    void followsCrossDomainRedirect() {
        // 무신사 OneLink·bit.ly 등 단축·딥링크는 다른 도메인의 최종 상품 페이지로 보낸다. 도메인이 바뀌어도 따라간다.
        // (사설망 SSRF 는 매 hop 의 guardAgainstInternalHost IP 가드가 막으므로 도메인 단위 차단은 불필요.)
        HttpPageFetcher fetcher =
            fetcherWith(server -> {
                server.expect(requestTo("https://musinsa.onelink.me/x"))
                    .andRespond(withStatus(HttpStatus.MOVED_PERMANENTLY).location(URI.create("https://musinsa.com/p")));
                server.expect(requestTo("https://musinsa.com/p"))
                    .andRespond(withSuccess("<html>product</html>", MediaType.TEXT_HTML));
            });

        PageContent page = fetcher.fetch(ProductLink.parse("https://musinsa.onelink.me/x"));

        assertEquals("<html>product</html>", page.html());
        assertEquals("https://musinsa.com/p", page.finalUrl().value().toString());
    }

    @Test
    @DisplayName("cross-domain redirect 타깃이 사설 IP 로 resolve 되면 SSRF 로 차단한다")
    void blocksCrossDomainRedirectToPrivateIp() {
        // cross-domain 을 허용해도 사설망 접근은 매 hop IP 가드가 막는다 — 도메인이 아니라 IP 로 SSRF 를 닫는다.
        RequestScopedDnsResolver.HostResolver resolver = host -> {
            if (host.equals("internal.attacker.test")) {
                return new InetAddress[] {InetAddress.getByName("169.254.169.254")}; // 클라우드 메타데이터
            }
            return new InetAddress[] {InetAddress.getByName("93.184.216.34")}; // 공인
        };
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://www.zigzag.kr/p"))
            .andRespond(withStatus(HttpStatus.FOUND).location(URI.create("https://internal.attacker.test/p")));
        HttpPageFetcher fetcher =
            new HttpPageFetcher(builder.build(), new RequestScopedDnsResolver(resolver), FetchProperties.defaults());

        PageFetchException ex = assertThrows(
            PageFetchException.class,
            () -> fetcher.fetch(ProductLink.parse("https://www.zigzag.kr/p")));
        // 사설/메타데이터 주소로 가는 hop 은 blockedHost(BLOCKED_HOST)로 차단된다.
        assertEquals(ExtractionErrorCode.BLOCKED_HOST, ex.code());
    }

    @Test
    @DisplayName("https 에서 http 로 다운그레이드하는 redirect 는 차단한다")
    void blocksHttpsToHttpDowngrade() {
        HttpPageFetcher fetcher =
            fetcherWith(server ->
                server.expect(requestTo("https://www.zigzag.kr/p"))
                    .andRespond(withStatus(HttpStatus.MOVED_PERMANENTLY).location(URI.create("http://zigzag.kr/p"))));

        assertThrows(
            PageFetchException.class,
            () -> fetcher.fetch(ProductLink.parse("https://www.zigzag.kr/p")));
    }

    @Test
    @DisplayName("redirect 가 상한을 넘으면 tooManyRedirects 로 끊는다")
    void breaksOnTooManyRedirects() {
        HttpPageFetcher fetcher =
            fetcherWith(server -> {
                // 같은 도메인 self-redirect 를 hop 상한+1 만큼. 끝내 페이지에 도달하지 못한다.
                for (int i = 0; i < 4; i++) {
                    server.expect(requestTo("https://zigzag.kr/loop"))
                        .andRespond(withStatus(HttpStatus.MOVED_PERMANENTLY).location(URI.create("https://zigzag.kr/loop")));
                }
            });

        PageFetchException ex = assertThrows(
            PageFetchException.class,
            () -> fetcher.fetch(ProductLink.parse("https://zigzag.kr/loop")));
        // redirect 루프는 재시도해도 결정론적 재실패라 확정 실패(TOO_MANY_REDIRECTS).
        assertEquals(ExtractionErrorCode.TOO_MANY_REDIRECTS, ex.code());
    }

    @Test
    @DisplayName("상대 경로 Location 도 원본 URI 에 resolve 해 따라간다")
    void resolvesRelativeLocation() {
        HttpPageFetcher fetcher =
            fetcherWith(server -> {
                server.expect(requestTo("https://zigzag.kr/old"))
                    .andRespond(withStatus(HttpStatus.MOVED_PERMANENTLY).location(URI.create("/new")));
                server.expect(requestTo("https://zigzag.kr/new"))
                    .andRespond(withSuccess("<html>moved</html>", MediaType.TEXT_HTML));
            });

        PageContent page = fetcher.fetch(ProductLink.parse("https://zigzag.kr/old"));

        assertEquals("<html>moved</html>", page.html());
    }

    @Test
    @DisplayName("redirect 없이 200 이면 바로 본문을 받는다")
    void returnsBodyDirectlyWithoutRedirect() {
        HttpPageFetcher fetcher =
            fetcherWith(server ->
                server.expect(requestTo("https://zigzag.kr/p"))
                    .andRespond(withSuccess("<html>direct</html>", MediaType.TEXT_HTML)));

        PageContent page = fetcher.fetch(ProductLink.parse("https://zigzag.kr/p"));

        assertEquals("<html>direct</html>", page.html());
        // redirect 가 없으면 finalUrl 은 원본 link 와 같다.
        assertEquals(page.link(), page.finalUrl());
    }
}
