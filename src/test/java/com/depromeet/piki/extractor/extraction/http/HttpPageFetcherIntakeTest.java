package com.depromeet.piki.extractor.extraction.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.depromeet.piki.extractor.common.exception.ExtractionErrorCode;
import com.depromeet.piki.extractor.domain.ProductLink;
import com.depromeet.piki.extractor.domain.ProductSnapshotException;
import com.depromeet.piki.extractor.extraction.PageContent;
import com.google.common.io.CountingInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.web.client.RestClient;

/**
 * 수신 단계의 두 방어 검증: 상품 페이지일 수 없는 응답을 <b>읽기 전에</b> 끊는가, 그리고 스트림이 바이트 상한에서
 * 멈추는가.
 *
 * <p>MockRestServiceServer 대신 응답 스트림을 직접 쥐는 rig 를 쓴다 - "본문을 안 읽었다"와 "여기까지만 읽었다"는
 * 실제로 읽힌 바이트 수로만 증명되고, 그 수는 스트림을 우리가 들고 있어야 셀 수 있다.
 */
class HttpPageFetcherIntakeTest {

    private static final String URL = "https://shop.example.com/p";

    /** 모든 host 를 공인 IP 로 해석해 SSRF 가드를 통과시킨다 - 여기선 수신 게이트만 격리해 본다. */
    private final RequestScopedDnsResolver.HostResolver publicIp =
        host -> new InetAddress[] {InetAddress.getByName("93.184.216.34")};

    @Test
    @DisplayName("영상·압축파일 응답은 본문을 한 바이트도 읽지 않고 상품 페이지가 아님으로 끊는다")
    void binaryContentTypeIsRejectedBeforeReadingBody() {
        for (String binary : new String[] {"video/mp4", "audio/mpeg", "image/jpeg", "application/zip",
            "application/pdf", "application/octet-stream"}) {
            CountingInputStream body = new CountingInputStream(new ByteArrayInputStream(new byte[4096]));
            HttpPageFetcher fetcher = fetcherReturning(body, MediaType.parseMediaType(binary), FetchProperties.defaults());

            ProductSnapshotException e = assertThrows(
                ProductSnapshotException.class,
                () -> fetcher.fetch(ProductLink.parse(URL)),
                binary
            );

            assertEquals(ExtractionErrorCode.NOT_PRODUCT_PAGE, e.code(), binary);
            assertEquals(0, body.getCount(), binary + " 본문을 읽지 않아야 한다");
        }
    }

    @Test
    @DisplayName("Content-Type 이 없어도 통과한다 - 무헤더로 HTML 을 주는 몰이 실재해 미상까지 막으면 recall 을 잃는다")
    void missingContentTypePasses() {
        PageContent page = fetch(html("<html><body>운동화</body></html>"), null);

        assertEquals("운동화", page.document().text());
    }

    @Test
    @DisplayName("text/plain 으로 온 HTML 도 통과한다 - 게이트는 명백한 바이너리만 막는다")
    void textPlainPasses() {
        PageContent page = fetch(html("<html><body>운동화</body></html>"), MediaType.TEXT_PLAIN);

        assertEquals("운동화", page.document().text());
    }

    @Test
    @DisplayName("바이트 상한을 넘으면 거기서 읽기를 끊는다 - 끝나지 않는 스트림은 read timeout 에 안 걸린다")
    void stopsReadingAtByteCap() {
        String body = "<html><body><p>" + "a".repeat(400) + "</p><p>TAIL</p></body></html>";
        CountingInputStream stream = new CountingInputStream(html(body));
        HttpPageFetcher fetcher = fetcherReturning(stream, MediaType.TEXT_HTML, propertiesWithMaxFetchBytes(100));

        PageContent page = fetcher.fetch(ProductLink.parse(URL));

        assertTrue(stream.getCount() <= 100, "상한을 넘겨 읽으면 안 된다 - 실제 " + stream.getCount());
        assertFalse(page.document().text().contains("TAIL"), "상한 뒤의 내용은 들어오지 않아야 한다");
    }

    @Test
    @DisplayName("본문을 읽는 중 연결이 끊기면 일시 실패다 - 대상 페이지의 확정된 문제가 아니라 재시도할 값이 있다")
    void bodyReadFailureIsTransient() {
        InputStream failing = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("connection reset");
            }
        };
        HttpPageFetcher fetcher = fetcherReturning(failing, MediaType.TEXT_HTML, FetchProperties.defaults());

        PageFetchException e = assertThrows(PageFetchException.class, () -> fetcher.fetch(ProductLink.parse(URL)));

        assertEquals(ExtractionErrorCode.UPSTREAM_ERROR, e.code());
        assertFalse(e.permanent());
    }

    private PageContent fetch(InputStream body, MediaType contentType) {
        return fetcherReturning(body, contentType, FetchProperties.defaults()).fetch(ProductLink.parse(URL));
    }

    private static InputStream html(String html) {
        return new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8));
    }

    private HttpPageFetcher fetcherReturning(InputStream body, MediaType contentType, FetchProperties properties) {
        MockClientHttpResponse response = new MockClientHttpResponse(body, HttpStatus.OK);
        if (contentType != null) {
            response.getHeaders().setContentType(contentType);
        }
        ClientHttpRequestFactory factory = (uri, method) -> {
            MockClientHttpRequest request = new MockClientHttpRequest(method, uri);
            request.setResponse(response);
            return request;
        };
        RestClient restClient = RestClient.builder().requestFactory(factory).build();
        return new HttpPageFetcher(restClient, new RequestScopedDnsResolver(publicIp), properties);
    }

    private static FetchProperties propertiesWithMaxFetchBytes(int maxFetchBytes) {
        FetchProperties defaults = FetchProperties.defaults();
        return new FetchProperties(
            defaults.userAgent(),
            Duration.ofSeconds(5),
            Duration.ofSeconds(15),
            Duration.ofSeconds(2),
            defaults.maxRedirects(),
            maxFetchBytes,
            defaults.maxRetainedChars()
        );
    }
}
