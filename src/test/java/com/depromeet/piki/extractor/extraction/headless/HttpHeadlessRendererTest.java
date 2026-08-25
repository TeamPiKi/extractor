package com.depromeet.piki.extractor.extraction.headless;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import com.depromeet.piki.extractor.extraction.PageContent;
import com.depromeet.piki.extractor.extraction.http.PageFetchException;
import com.depromeet.piki.extractor.extraction.http.RequestScopedDnsResolver;
import com.github.luben.zstd.ZstdOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * POST /render 의 wire 계약(요청 필드·verdict 번역·SSRF 가드·final_url 폴백·html 상한·zstd 해제)을 네트워크
 * 없이 검증한다.
 *
 * <p>verdict 를 계약으로 번역하는 것이 렌더러의 단일 책임이라, 소비자(HeadlessProductLinkExtractor)는 여기서
 * 통과하지 못한 PageContent 를 절대 받지 않는다. DNS 는 가짜 공인 IP 로 주입해 SSRF 가드를 통과시킨다
 * (HttpPageFetcher 테스트와 같은 방식).
 */
class HttpHeadlessRendererTest {

    private static final String BASE_URL = "http://headless.test:8000";

    private final ProductLink link = ProductLink.parse("https://kream.co.kr/products/6963");

    private final RequestScopedDnsResolver.HostResolver publicIp =
        host -> new InetAddress[] {InetAddress.getByName("93.184.216.34")};

    private HttpHeadlessRenderer rendererWith(
        HeadlessExtractionProperties properties,
        RequestScopedDnsResolver.HostResolver hostResolver,
        ZstdDictionaries dictionaries,
        Consumer<MockRestServiceServer> configure
    ) {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        configure.accept(server);
        return new HttpHeadlessRenderer(
            builder.build(),
            properties,
            new RequestScopedDnsResolver(hostResolver),
            new ObjectMapper(),
            dictionaries
        );
    }

    private HttpHeadlessRenderer rendererWith(Consumer<MockRestServiceServer> configure) {
        return rendererWith(HeadlessExtractionProperties.of(true), publicIp, ZstdDictionaries.none(), configure);
    }

    @Test
    @DisplayName("OK 렌더는 url·include_html=true·compress=true 로 요청하고, html 과 final_url 기준 PageContent 를 돌려준다")
    void okRenderReturnsPageContent() {
        String html = "<html><body>rendered</body></html>";
        // X-Encoding 헤더 없는 plain JSON 응답 — compress 필드를 모르는 구버전 renderer 호환 경로이기도 하다.
        HttpHeadlessRenderer renderer = rendererWith(server -> server
            .expect(requestTo(BASE_URL + "/render"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(jsonPath("$.url").value(link.value().toString()))
            // 파싱(구조화/LLM)은 우리가 하므로 렌더된 HTML 을 항상 요구한다.
            .andExpect(jsonPath("$.include_html").value(true))
            // 서버간 전송량 절감을 위해 응답 zstd 압축을 요청한다.
            .andExpect(jsonPath("$.compress").value(true))
            .andRespond(withSuccess(
                "{\"verdict\":\"OK\",\"proxied\":true,\"status\":200,"
                    + "\"final_url\":\"https://kream.co.kr/products/6963?after-redirect\",\"html\":\"" + html.replace("\"", "\\\"") + "\"}",
                MediaType.APPLICATION_JSON
            )));

        PageContent page = renderer.render(link, false);

        assertEquals("rendered", page.document().text());
        // 정체성(원본 link)은 유지하고, baseUri 용 finalUrl 은 렌더가 따라간 최종 URL 을 쓴다.
        assertEquals(link, page.link());
        assertEquals("https://kream.co.kr/products/6963?after-redirect", page.finalUrl().value().toString());
    }

    @Test
    @DisplayName("BLOCK 아닌 어떤 verdict 든(구계약 잔재 PARTIAL·미지 포함) html 이 있으면 진행한다 — verdict 로 HTML 을 버리면 recall 을 잃는다")
    void anyNonBlockVerdictWithHtmlProceeds() {
        for (String verdict : List.of("PARTIAL", "EMPTY", "SOMETHING_NEW")) {
            HttpHeadlessRenderer renderer = rendererWith(server -> server
                .expect(requestTo(BASE_URL + "/render"))
                .andRespond(withSuccess(
                    "{\"verdict\":\"" + verdict + "\",\"html\":\"<html>rendered dom</html>\"}",
                    MediaType.APPLICATION_JSON
                )));

            assertEquals("rendered dom", renderer.render(link, false).document().text(), "verdict=" + verdict);
        }
    }

    @Test
    @DisplayName("BLOCK 은 일시 실패(HEADLESS_BLOCKED)다 — 렌더 서비스 BLOCK 판정에 429·일시 챌린지가 섞여 fail-safe 로 일시")
    void blockIsTransient() {
        HttpHeadlessRenderer renderer = rendererWith(server -> server
            .expect(requestTo(BASE_URL + "/render"))
            .andRespond(withSuccess("{\"verdict\":\"BLOCK\",\"status\":429}", MediaType.APPLICATION_JSON)));

        HeadlessRenderException ex = assertThrows(HeadlessRenderException.class, () -> renderer.render(link, false));

        assertEquals(ExtractionErrorCode.HEADLESS_BLOCKED, ex.code());
        assertFalse(ex.permanent());
    }

    @Test
    @DisplayName("html 이 없으면(브라우저 오류 ERROR·빈 렌더 EMPTY) 일시 실패(HEADLESS_UPSTREAM)다")
    void missingHtmlIsTransient() {
        for (String body : List.of(
            "{\"verdict\":\"ERROR\",\"error\":\"TimeoutError: boom\"}",
            "{\"verdict\":\"OK\"}",
            "{\"verdict\":\"EMPTY\",\"html\":\"\"}"
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
    @DisplayName("zstd 압축 응답(X-Encoding: zstd, 사전 없음)은 해제해 plain JSON 과 같은 계약으로 처리한다")
    void zstdResponseIsDecompressed() {
        byte[] packed = zstdCompress("{\"verdict\":\"OK\",\"html\":\"<html>compressed dom</html>\"}", null);
        HttpHeadlessRenderer renderer = rendererWith(server -> server
            .expect(requestTo(BASE_URL + "/render"))
            .andRespond(withSuccess(packed, MediaType.APPLICATION_OCTET_STREAM).headers(zstdHeaders(""))));

        assertEquals("compressed dom", renderer.render(link, false).document().text());
    }

    @Test
    @DisplayName("사전 압축 응답(X-Zstd-Dict: 사전ID)은 보유한 같은 사전으로 해제한다")
    void zstdDictResponseUsesSharedDictionary() {
        byte[] dict = "<html><head><meta charset=\"utf-8\"><title>공용 boilerplate</title>".getBytes(StandardCharsets.UTF_8);
        byte[] packed = zstdCompress("{\"verdict\":\"OK\",\"html\":\"<html>dict compressed</html>\"}", dict);
        HttpHeadlessRenderer renderer = rendererWith(
            HeadlessExtractionProperties.of(true),
            publicIp,
            ZstdDictionaries.of("mall-v1.dict", dict),
            server -> server
                .expect(requestTo(BASE_URL + "/render"))
                .andRespond(withSuccess(packed, MediaType.APPLICATION_OCTET_STREAM).headers(zstdHeaders("mall-v1.dict")))
        );

        assertEquals("dict compressed", renderer.render(link, false).document().text());
    }

    @Test
    @DisplayName("미보유 사전ID 는 일시 실패(HEADLESS_UPSTREAM)다 — 사전은 extractor 에 먼저 배포하는 롤아웃 규약 위반 신호")
    void unknownZstdDictIsTransient() {
        byte[] packed = zstdCompress("{\"verdict\":\"OK\",\"html\":\"<html>x</html>\"}", null);
        HttpHeadlessRenderer renderer = rendererWith(server -> server
            .expect(requestTo(BASE_URL + "/render"))
            .andRespond(withSuccess(packed, MediaType.APPLICATION_OCTET_STREAM).headers(zstdHeaders("future-v2.dict"))));

        HeadlessRenderException ex = assertThrows(HeadlessRenderException.class, () -> renderer.render(link, false));

        assertEquals(ExtractionErrorCode.HEADLESS_UPSTREAM, ex.code());
        assertFalse(ex.permanent());
    }

    @Test
    @DisplayName("zstd 해제 실패(손상 바이트)는 일시 실패(HEADLESS_UPSTREAM)다")
    void corruptZstdIsTransient() {
        byte[] garbage = {1, 2, 3, 4, 5};
        HttpHeadlessRenderer renderer = rendererWith(server -> server
            .expect(requestTo(BASE_URL + "/render"))
            .andRespond(withSuccess(garbage, MediaType.APPLICATION_OCTET_STREAM).headers(zstdHeaders(""))));

        HeadlessRenderException ex = assertThrows(HeadlessRenderException.class, () -> renderer.render(link, false));

        assertEquals(ExtractionErrorCode.HEADLESS_UPSTREAM, ex.code());
        assertFalse(ex.permanent());
    }

    @Test
    @DisplayName("JSON 이 아닌 응답 body 는 일시 실패(HEADLESS_UPSTREAM)다")
    void nonJsonBodyIsTransient() {
        HttpHeadlessRenderer renderer = rendererWith(server -> server
            .expect(requestTo(BASE_URL + "/render"))
            .andRespond(withSuccess("not-json", MediaType.TEXT_PLAIN)));

        HeadlessRenderException ex = assertThrows(HeadlessRenderException.class, () -> renderer.render(link, false));

        assertEquals(ExtractionErrorCode.HEADLESS_UPSTREAM, ex.code());
        assertFalse(ex.permanent());
    }

    @Test
    @DisplayName("내부망으로 resolve 되는 host 는 렌더 서비스 호출 전에 SSRF 로 차단된다 — 직행 경로의 방어선")
    void internalHostIsBlockedBeforeRender() {
        RequestScopedDnsResolver.HostResolver internalIp =
            host -> new InetAddress[] {InetAddress.getByName("169.254.169.254")};
        // 서버에 expect 를 하나도 걸지 않는다 — 가드가 먼저 던지므로 렌더 서비스로 요청이 나가면 안 된다.
        HttpHeadlessRenderer renderer =
            rendererWith(HeadlessExtractionProperties.of(true), internalIp, ZstdDictionaries.none(), server -> { });

        PageFetchException ex = assertThrows(PageFetchException.class, () -> renderer.render(link, false));

        assertEquals(ExtractionErrorCode.BLOCKED_HOST, ex.code());
        assertTrue(ex.permanent());
    }

    @Test
    @DisplayName("final_url 이 없거나 우리 형식이 아니면 원본 link 로 폴백한다 — 렌더 성공을 실패로 오판하지 않는다")
    void invalidFinalUrlFallsBackToLink() {
        for (String finalUrlField : List.of("", ",\"final_url\":\"chrome-error://failed\"")) {
            HttpHeadlessRenderer renderer = rendererWith(server -> server
                .expect(requestTo(BASE_URL + "/render"))
                .andRespond(withSuccess(
                    "{\"verdict\":\"OK\",\"html\":\"<html>ok</html>\"" + finalUrlField + "}",
                    MediaType.APPLICATION_JSON
                )));

            assertEquals(link, renderer.render(link, false).finalUrl());
        }
    }

    @Test
    @DisplayName("final_url 이 내부망으로 resolve 되면 렌더 전체를 거부한다 — 원본만 검증하면 redirect 로 가드를 우회한다")
    void internalFinalUrlIsBlocked() {
        // 원본 host 는 공인 IP, 렌더 서비스가 따라간 최종 host 만 내부망인 상황 — 정적 fetch 가 매 hop 을
        // 검증하는 것과 달리 여기엔 검증이 없어, 내부망 응답이 상품 HTML 로 흘러들 수 있었다.
        RequestScopedDnsResolver.HostResolver byHost = host -> "metadata.internal".equals(host)
            ? new InetAddress[] {InetAddress.getByName("169.254.169.254")}
            : new InetAddress[] {InetAddress.getByName("93.184.216.34")};

        HttpHeadlessRenderer renderer = rendererWith(
            HeadlessExtractionProperties.of(true), byHost, ZstdDictionaries.none(), server -> server
                .expect(requestTo(BASE_URL + "/render"))
                .andRespond(withSuccess(
                    "{\"verdict\":\"OK\",\"html\":\"<html>ok</html>\","
                        + "\"final_url\":\"https://metadata.internal/latest/meta-data/\"}",
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
    @DisplayName("렌더된 HTML 도 정적 fetch 와 같은 가지치기를 통과한다 - 두 전략이 하류에 넘기는 문서 모양이 갈리면 안 된다")
    void renderedHtmlIsPruned() {
        HttpHeadlessRenderer renderer = rendererWith(server -> server
            .expect(requestTo(BASE_URL + "/render"))
            .andRespond(withSuccess(
                "{\"verdict\":\"OK\",\"html\":\"<html><head><style>.a{color:red}</style>"
                    + "<script>var x = 1;</script></head><body><p>운동화</p></body></html>\"}",
                MediaType.APPLICATION_JSON
            )));

        Document document = renderer.render(link, false).document();

        assertNull(document.selectFirst("style"), "style 은 버려져야 한다");
        assertNull(document.selectFirst("script"), "데이터 아닌 script 는 버려져야 한다");
        assertEquals("운동화", document.text(), "본문은 남아야 한다");
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
