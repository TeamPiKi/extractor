package com.depromeet.piki.extractor.extraction.http;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.depromeet.piki.extractor.domain.ProductLink;
import com.depromeet.piki.extractor.extraction.PageContent;
import java.net.InetAddress;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

// 응답 charset 디코딩 검증: 응답 Content-Type charset → HTML meta charset → UTF-8 순.
// RestClient 의 기본 String 변환은 Content-Type 에 charset 이 없으면 ISO-8859-1 로 떨어져 UTF-8 한글이 깨졌다(카카오).
// 바이트로 받아 직접 charset 을 정해 디코딩하는지 본다. 네트워크 없이 MockRestServiceServer 로 응답을 제어한다.
class HttpPageFetcherCharsetTest {

    // 모든 host 를 공인 IP 로 해석해 SSRF 가드를 통과시킨다(여기선 charset 디코딩만 검증).
    private final RequestScopedDnsResolver.HostResolver publicIp =
        host -> new InetAddress[] {InetAddress.getByName("93.184.216.34")};

    private HttpPageFetcher fetcherReturning(byte[] bytes, MediaType contentType) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://shop.example.com/p")).andRespond(withSuccess(bytes, contentType));
        return new HttpPageFetcher(builder.build(), new RequestScopedDnsResolver(publicIp), FetchProperties.defaults());
    }

    @Test
    @DisplayName("Content-Type 에 charset 이 없는 UTF-8 페이지의 한글이 깨지지 않는다")
    void utf8WithoutHeaderCharsetKeepsKorean() {
        // 카카오류: UTF-8 인데 Content-Type 에 charset 이 없는 페이지. 헤더 charset null → meta 없음 → UTF-8 폴백.
        String html = "<html><head><meta property=\"og:title\" content=\"나이키 운동화\"></head><body></body></html>";
        HttpPageFetcher fetcher = fetcherReturning(html.getBytes(StandardCharsets.UTF_8), MediaType.TEXT_HTML);

        PageContent page = fetcher.fetch(ProductLink.parse("https://shop.example.com/p"));

        assertTrue(page.html().contains("나이키 운동화"), "charset 없는 UTF-8 응답이 UTF-8 로 디코딩돼 한글이 보존돼야 한다");
    }

    @Test
    @DisplayName("Content-Type 의 charset(EUC-KR)을 따라 디코딩한다")
    void followsHeaderCharsetEucKr() {
        Charset euckr = Charset.forName("EUC-KR");
        String html = "<html><body>운동화</body></html>";
        HttpPageFetcher fetcher = fetcherReturning(html.getBytes(euckr), MediaType.parseMediaType("text/html;charset=EUC-KR"));

        PageContent page = fetcher.fetch(ProductLink.parse("https://shop.example.com/p"));

        assertTrue(page.html().contains("운동화"), "응답 Content-Type 의 EUC-KR charset 으로 디코딩돼야 한다");
    }

    @Test
    @DisplayName("Content-Type 에 charset 이 없으면 HTML meta charset(EUC-KR)을 따른다")
    void fallsBackToMetaCharset() {
        Charset euckr = Charset.forName("EUC-KR");
        String html = "<html><head><meta charset=\"euc-kr\"></head><body>장바구니</body></html>";
        // 헤더에 charset 없음(text/html) → HTML meta 의 euc-kr 을 감지해 디코딩해야 한다.
        HttpPageFetcher fetcher = fetcherReturning(html.getBytes(euckr), MediaType.TEXT_HTML);

        PageContent page = fetcher.fetch(ProductLink.parse("https://shop.example.com/p"));

        assertTrue(page.html().contains("장바구니"), "헤더 charset 이 없으면 HTML meta charset(euc-kr)으로 디코딩돼야 한다");
    }

    @Test
    @DisplayName("Content-Type charset 이 HTML meta charset 보다 우선한다")
    void headerCharsetTakesPrecedenceOverMeta() {
        // Content-Type 은 UTF-8, meta 는 euc-kr 로 충돌. HTML5 spec 처럼 HTTP 헤더 charset 이 meta 보다 우선이라 UTF-8 로 디코딩돼야 한다.
        String html = "<html><head><meta charset=\"euc-kr\"></head><body>운동화</body></html>";
        HttpPageFetcher fetcher =
            fetcherReturning(html.getBytes(StandardCharsets.UTF_8), MediaType.parseMediaType("text/html;charset=UTF-8"));

        PageContent page = fetcher.fetch(ProductLink.parse("https://shop.example.com/p"));

        assertTrue(page.html().contains("운동화"), "Content-Type charset(UTF-8)이 HTML meta charset(euc-kr)보다 우선해야 한다");
    }
}
