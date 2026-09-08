package com.depromeet.piki.extractor.extraction.headless;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.depromeet.piki.extractor.common.exception.ExtractionErrorCode;
import com.depromeet.piki.extractor.domain.ProductLink;
import com.depromeet.piki.extractor.extraction.HeadlessExtractionProperties;
import com.depromeet.piki.extractor.extraction.http.PageFetchException;
import com.depromeet.piki.extractor.extraction.http.RequestScopedDnsResolver;
import com.github.luben.zstd.ZstdOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * POST /render 의 wire 계약(요청 필드·홉 변환·SSRF 가드·홉 url 폴백·zstd 해제)을 네트워크 없이 검증한다.
 * 홉의 해석은 HeadlessProductLinkExtractorTest 가 진다. DNS 는 가짜 공인 IP 로 주입해 SSRF 가드를 통과시킨다.
 */
class HttpHeadlessRendererTest {

    private static final String BASE_URL = "http://headless.test:8000";
    private static final String TWO_HOPS =
        "{\"proxied\":true,\"hops\":["
            + "{\"url\":\"https://kream.co.kr/products/6963\",\"status\":302,\"headers\":{\"location\":\"/p\"}},"
            + "{\"url\":\"https://kream.co.kr/p\",\"status\":200,\"headers\":{\"content-type\":\"text/html\"},"
            + "\"body\":\"<html>ssr</html>\",\"dom\":\"<html>dom</html>\"}]}";

    private final ProductLink link = ProductLink.parse("https://kream.co.kr/products/6963");

    private final RequestScopedDnsResolver.HostResolver publicIp =
        host -> new InetAddress[] {InetAddress.getByName("93.184.216.34")};

    private HttpHeadlessRenderer rendererWith(
        RequestScopedDnsResolver.HostResolver hostResolver,
        ZstdDictionaries dictionaries,
        Consumer<MockRestServiceServer> configure
    ) {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        configure.accept(server);
        return new HttpHeadlessRenderer(
            builder.build(),
            HeadlessExtractionProperties.of(true),
            new RequestScopedDnsResolver(hostResolver),
            new ObjectMapper(),
            dictionaries
        );
    }

    private HttpHeadlessRenderer rendererWith(Consumer<MockRestServiceServer> configure) {
        return rendererWith(publicIp, ZstdDictionaries.none(), configure);
    }

    @Test
    @DisplayName("url·compress=true 로 요청하고, 홉을 순서·status·headers·body·dom 그대로 옮긴다")
    void hopsAreMappedInOrder() {
        HttpHeadlessRenderer renderer = rendererWith(server -> server
            .expect(requestTo(BASE_URL + "/render"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(jsonPath("$.url").value(link.value().toString()))
            .andExpect(jsonPath("$.compress").value(true))
            .andRespond(withSuccess(TWO_HOPS, MediaType.APPLICATION_JSON)));

        List<RenderedHop> hops = renderer.render(link, false);

        assertEquals(2, hops.size());
        assertEquals(new RenderedHop(link, 302, Map.of("location", "/p"), "", ""), hops.getFirst());
        RenderedHop last = hops.getLast();
        assertEquals("https://kream.co.kr/p", last.url().value().toString());
        assertEquals(200, last.status());
        assertEquals("<html>ssr</html>", last.body());
        assertEquals("<html>dom</html>", last.dom());
    }

    @Test
    @DisplayName("홉이 없으면(브라우저 오류·빈 응답) 일시 실패(HEADLESS_UPSTREAM)다")
    void noHopsIsTransient() {
        for (String body : List.of(
            "{\"error\":\"TimeoutError: boom\",\"hops\":[]}",
            "{\"error\":\"TimeoutError: boom\"}",
            "{}"
        )) {
            HttpHeadlessRenderer renderer = rendererWith(server -> server
                .expect(requestTo(BASE_URL + "/render"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON)));

            HeadlessRenderException ex = assertThrows(HeadlessRenderException.class, () -> renderer.render(link, false));

            assertEquals(ExtractionErrorCode.HEADLESS_UPSTREAM, ex.code());
            assertFalse(ex.permanent());
        }
    }

    @Test
    @DisplayName("홉 계약 이전 renderer 의 html·final_url 응답은 홉 하나로 읽는다 — renderer 배포가 수동이라 뒤처질 수 있다")
    void legacySingleHtmlBecomesOneHop() {
        HttpHeadlessRenderer renderer = rendererWith(server -> server
            .expect(requestTo(BASE_URL + "/render"))
            .andRespond(withSuccess(
                "{\"verdict\":\"OK\",\"status\":200,\"final_url\":\"https://kream.co.kr/p\",\"html\":\"<html>legacy</html>\"}",
                MediaType.APPLICATION_JSON
            )));

        List<RenderedHop> hops = renderer.render(link, false);

        assertEquals(1, hops.size());
        assertEquals("https://kream.co.kr/p", hops.getFirst().url().value().toString());
        assertEquals(200, hops.getFirst().status());
        assertEquals("<html>legacy</html>", hops.getFirst().dom());
    }

    @Test
    @DisplayName("구계약 renderer 의 verdict=BLOCK 은 html 이 실려 와도 일시 실패(HEADLESS_BLOCKED)다 — 챌린지 페이지를 내용으로 흘리지 않는다")
    void legacyBlockVerdictIsTransient() {
        for (String body : List.of(
            "{\"verdict\":\"BLOCK\",\"status\":429}",
            "{\"verdict\":\"BLOCK\",\"status\":200,\"html\":\"<html><title>ok</title>challenge</html>\"}"
        )) {
            HttpHeadlessRenderer renderer = rendererWith(server -> server
                .expect(requestTo(BASE_URL + "/render"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON)));

            HeadlessRenderException ex = assertThrows(HeadlessRenderException.class, () -> renderer.render(link, false));

            assertEquals(ExtractionErrorCode.HEADLESS_BLOCKED, ex.code());
        }
    }

    @Test
    @DisplayName("zstd 압축 응답(X-Encoding: zstd, 사전 없음)은 해제해 plain JSON 과 같은 계약으로 처리한다")
    void zstdResponseIsDecompressed() {
        HttpHeadlessRenderer renderer = rendererWith(server -> server
            .expect(requestTo(BASE_URL + "/render"))
            .andRespond(withSuccess(zstdCompress(TWO_HOPS, null), MediaType.APPLICATION_OCTET_STREAM)
                .headers(zstdHeaders(""))));

        assertEquals("<html>dom</html>", renderer.render(link, false).getLast().dom());
    }

    @Test
    @DisplayName("사전 압축 응답(X-Zstd-Dict: 사전ID)은 보유한 같은 사전으로 해제한다")
    void zstdDictResponseUsesSharedDictionary() {
        byte[] dict = "<html><head><meta charset=\"utf-8\"><title>공용 boilerplate</title>".getBytes(StandardCharsets.UTF_8);
        HttpHeadlessRenderer renderer = rendererWith(publicIp, ZstdDictionaries.of("mall-v1.dict", dict), server -> server
            .expect(requestTo(BASE_URL + "/render"))
            .andRespond(withSuccess(zstdCompress(TWO_HOPS, dict), MediaType.APPLICATION_OCTET_STREAM)
                .headers(zstdHeaders("mall-v1.dict"))));

        assertEquals("<html>dom</html>", renderer.render(link, false).getLast().dom());
    }

    @Test
    @DisplayName("미보유 사전ID 는 일시 실패(HEADLESS_UPSTREAM)다 — 사전은 extractor 에 먼저 배포하는 롤아웃 규약 위반 신호")
    void unknownZstdDictIsTransient() {
        HttpHeadlessRenderer renderer = rendererWith(server -> server
            .expect(requestTo(BASE_URL + "/render"))
            .andRespond(withSuccess(zstdCompress(TWO_HOPS, null), MediaType.APPLICATION_OCTET_STREAM)
                .headers(zstdHeaders("future-v2.dict"))));

        HeadlessRenderException ex = assertThrows(HeadlessRenderException.class, () -> renderer.render(link, false));

        assertEquals(ExtractionErrorCode.HEADLESS_UPSTREAM, ex.code());
    }

    @Test
    @DisplayName("zstd 해제 실패(손상 바이트)·JSON 아닌 body 는 일시 실패(HEADLESS_UPSTREAM)다")
    void undecodableBodyIsTransient() {
        for (Consumer<MockRestServiceServer> configure : List.<Consumer<MockRestServiceServer>>of(
            server -> server.expect(requestTo(BASE_URL + "/render"))
                .andRespond(withSuccess(new byte[] {1, 2, 3, 4, 5}, MediaType.APPLICATION_OCTET_STREAM).headers(zstdHeaders(""))),
            server -> server.expect(requestTo(BASE_URL + "/render"))
                .andRespond(withSuccess("not-json", MediaType.TEXT_PLAIN))
        )) {
            HttpHeadlessRenderer renderer = rendererWith(configure);

            HeadlessRenderException ex = assertThrows(HeadlessRenderException.class, () -> renderer.render(link, false));

            assertEquals(ExtractionErrorCode.HEADLESS_UPSTREAM, ex.code());
            assertFalse(ex.permanent());
        }
    }

    @Test
    @DisplayName("내부망으로 resolve 되는 host 는 렌더 서비스 호출 전에 SSRF 로 차단된다 — 직행 경로의 방어선")
    void internalHostIsBlockedBeforeRender() {
        RequestScopedDnsResolver.HostResolver internalIp =
            host -> new InetAddress[] {InetAddress.getByName("169.254.169.254")};
        // 서버에 expect 를 하나도 걸지 않는다 — 가드가 먼저 던지므로 렌더 서비스로 요청이 나가면 안 된다.
        HttpHeadlessRenderer renderer = rendererWith(internalIp, ZstdDictionaries.none(), server -> { });

        PageFetchException ex = assertThrows(PageFetchException.class, () -> renderer.render(link, false));

        assertEquals(ExtractionErrorCode.BLOCKED_HOST, ex.code());
        assertTrue(ex.permanent());
    }

    @Test
    @DisplayName("홉 url 이 없거나 우리 형식이 아니면 원본 link 로 폴백한다 — 렌더 성공을 실패로 오판하지 않는다")
    void invalidHopUrlFallsBackToLink() {
        for (String urlField : List.of("", "\"url\":\"chrome-error://failed\",")) {
            HttpHeadlessRenderer renderer = rendererWith(server -> server
                .expect(requestTo(BASE_URL + "/render"))
                .andRespond(withSuccess(
                    "{\"hops\":[{" + urlField + "\"status\":200,\"dom\":\"<html>ok</html>\"}]}",
                    MediaType.APPLICATION_JSON
                )));

            assertEquals(link, renderer.render(link, false).getFirst().url());
        }
    }

    @Test
    @DisplayName("어느 홉이든 내부망으로 resolve 되면 렌더 전체를 거부한다 — 원본만 검증하면 redirect 로 가드를 우회한다")
    void internalHopIsBlocked() {
        RequestScopedDnsResolver.HostResolver byHost = host -> "metadata.internal".equals(host)
            ? new InetAddress[] {InetAddress.getByName("169.254.169.254")}
            : new InetAddress[] {InetAddress.getByName("93.184.216.34")};
        HttpHeadlessRenderer renderer = rendererWith(byHost, ZstdDictionaries.none(), server -> server
            .expect(requestTo(BASE_URL + "/render"))
            .andRespond(withSuccess(
                "{\"hops\":["
                    + "{\"url\":\"https://metadata.internal/latest/meta-data/\",\"status\":200,\"dom\":\"<html>secret</html>\"},"
                    + "{\"url\":\"https://kream.co.kr/p\",\"status\":200,\"dom\":\"<html>ok</html>\"}]}",
                MediaType.APPLICATION_JSON
            )));

        PageFetchException ex = assertThrows(PageFetchException.class, () -> renderer.render(link, false));

        assertEquals(ExtractionErrorCode.BLOCKED_HOST, ex.code());
    }

    @Test
    @DisplayName("렌더 서비스의 5xx·비정상 응답은 일시 실패다")
    void renderServiceErrorIsTransient() {
        HttpHeadlessRenderer renderer = rendererWith(server -> server
            .expect(requestTo(BASE_URL + "/render"))
            .andRespond(withServerError()));

        HeadlessRenderException ex = assertThrows(HeadlessRenderException.class, () -> renderer.render(link, false));

        assertEquals(ExtractionErrorCode.HEADLESS_UPSTREAM, ex.code());
        assertFalse(ex.permanent());
    }

    @Test
    @DisplayName("렌더 error 원문의 URL(쿼리스트링 포함)은 마스킹된다 — playwright 예외가 대상 URL 을 통째로 싣는다")
    void errorUrlsAreMasked() {
        String masked = HttpHeadlessRenderer.maskUrls(
            "TimeoutError: Page.goto: navigating to \"https://shop.example.com/p?token=secret123\", waiting until commit"
        );

        assertFalse(masked.contains("token=secret123"));
        assertTrue(masked.contains("<url>"));
        assertEquals(null, HttpHeadlessRenderer.maskUrls(null));
    }

    /** renderer 의 compress.py 와 대칭인 압축 — 사전을 주면 사전 압축, null 이면 plain zstd. */
    private static byte[] zstdCompress(String json, byte[] dict) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZstdOutputStream zstd = new ZstdOutputStream(bytes)) {
            if (dict != null) {
                zstd.setDict(dict);
            }
            zstd.write(json.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    private static HttpHeaders zstdHeaders(String dictId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Encoding", "zstd");
        headers.set("X-Zstd-Dict", dictId);
        return headers;
    }
}
