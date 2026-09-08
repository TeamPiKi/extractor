package com.depromeet.piki.extractor.extraction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.depromeet.piki.extractor.common.exception.ExtractionErrorCode;
import com.depromeet.piki.extractor.domain.ProductLink;
import com.depromeet.piki.extractor.domain.ProductSnapshot;
import com.depromeet.piki.extractor.domain.ProductSnapshotException;
import com.depromeet.piki.extractor.extraction.gemini.GeminiExtractionResult;
import com.depromeet.piki.extractor.extraction.headless.HeadlessRenderException;
import com.depromeet.piki.extractor.extraction.headless.HeadlessRenderer;
import com.depromeet.piki.extractor.extraction.headless.RenderedHop;
import com.depromeet.piki.extractor.extraction.structured.StructuredDataExtractor;
import com.depromeet.piki.extractor.support.StubGeminiClient;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * 헤드리스 전략의 홉 해석 — 어느 홉의 어느 문서를 파이프라인에 태우는지, 차단·빈 홉을 어떻게 닫는지 본다.
 * 렌더 wire 는 HttpHeadlessRendererTest 가, 직행/에스컬레이션 라우팅은 FallbackProductLinkExtractorTest 가 진다.
 */
class HeadlessProductLinkExtractorTest {

    private static final String SHELL = "<html><head><script src=\"/app.js\"></script></head><body><div id=\"root\"></div></body></html>";
    private static final String CHALLENGE = "<html><head><title>보안 확인 중..</title></head><body>" + "확인 ".repeat(200) + "</body></html>";

    private final ProductLink link = ProductLink.parse("https://m.a-bly.com/goods/1");
    private final ProductLink mobile = ProductLink.parse("https://mobile.a-bly.com/goods/1");
    private final ProductLink feed = ProductLink.parse("https://mobile.a-bly.com/today");

    private final StubGeminiClient stubGemini = new StubGeminiClient();

    private static String product(String name, int price) {
        return "<html><head><script type=\"application/ld+json\">"
            + "{\"@type\":\"Product\",\"name\":\"" + name + "\",\"image\":\"https://cdn.example.com/p.png\","
            + "\"offers\":{\"price\":\"" + price + "\",\"priceCurrency\":\"KRW\"}}"
            + "</script></head><body></body></html>";
    }

    private static RenderedHop hop(ProductLink url, int status, String body, String dom) {
        return new RenderedHop(url, status, Map.of(), body, dom);
    }

    private HeadlessProductLinkExtractor extractorWith(Function<ProductLink, List<RenderedHop>> render) {
        HeadlessRenderer renderer = (target, authorized) -> render.apply(target);
        StructuredDataExtractor structured = new StructuredDataExtractor(new ObjectMapper());
        return new HeadlessProductLinkExtractor(
            renderer,
            structured,
            new HtmlSnapshotPipeline(structured, new GeminiHtmlExtractor(stubGemini), new SimpleMeterRegistry())
        );
    }

    @Test
    @DisplayName("서버 본문이 셸이어도 같은 홉의 dom 에 구조화 데이터가 있으면 그것으로 LLM 없이 스냅샷을 만든다")
    void domBeatsShellBody() {
        HeadlessProductLinkExtractor extractor = extractorWith(l -> List.of(
            hop(link, 302, "", ""),
            hop(mobile, 200, SHELL, product("가죽 벨트", 6_380))
        ));

        ProductSnapshot snapshot = extractor.extract(link, false, null);

        assertEquals("가죽 벨트", snapshot.name());
        assertEquals(6_380, snapshot.currentPrice());
        assertEquals(mobile, snapshot.finalUrl());
        assertEquals(0, stubGemini.invocations());
    }

    @Test
    @DisplayName("홈 피드로 튕겨 나간 뒤여도 앞 홉에 남은 상품 문서를 찾는다 — 마지막 홉이 곧 상품이 아니다")
    void productInEarlierHopWins() {
        HeadlessProductLinkExtractor extractor = extractorWith(l -> List.of(
            hop(mobile, 200, SHELL, product("앞 홉 상품", 10_000)),
            hop(feed, 200, "<html><body>" + "추천 피드 ".repeat(100) + "</body></html>", "<html><body>" + "추천 피드 ".repeat(100) + "</body></html>")
        ));

        ProductSnapshot snapshot = extractor.extract(link, false, null);

        assertEquals("앞 홉 상품", snapshot.name());
        assertEquals(0, stubGemini.invocations());
    }

    @Test
    @DisplayName("구조화 데이터가 어디에도 없으면 마지막 홉의 dom 한 장으로만 LLM fallback 을 탄다")
    void llmFallbackUsesLastDomOnce() {
        stubGemini.build = request -> new GeminiExtractionResult(true, "엘엘엠 상품", 50_000, "KRW", "https://cdn.example.com/i.png");
        String text = "<html><body>" + "구조화 데이터 없이 렌더된 상품 상세 설명 텍스트. ".repeat(3) + "</body></html>";
        HeadlessProductLinkExtractor extractor = extractorWith(l -> List.of(hop(link, 200, text, text), hop(mobile, 200, text, text)));

        ProductSnapshot snapshot = extractor.extract(link, false, null);

        assertEquals("엘엘엠 상품", snapshot.name());
        assertEquals(mobile, snapshot.finalUrl());
        assertEquals(1, stubGemini.invocations());
    }

    @Test
    @DisplayName("dom 까지 셸이면 LLM 호출 없이 NO_EXTRACTABLE_CONTENT 로 닫는다 — 헤드리스는 마지막 수단이라 재분류가 없다")
    void shellEverywhereFailsWithoutLlm() {
        HeadlessProductLinkExtractor extractor = extractorWith(l -> List.of(hop(mobile, 200, SHELL, SHELL)));

        ProductSnapshotException e = assertThrows(ProductSnapshotException.class, () -> extractor.extract(link, false, null));

        assertEquals(ExtractionErrorCode.NO_EXTRACTABLE_CONTENT, e.code());
        assertEquals(0, stubGemini.invocations());
    }

    @Test
    @DisplayName("모든 홉이 차단 status 거나 챌린지 title 이면 일시 실패(HEADLESS_BLOCKED)다")
    void allHopsBlockedIsTransient() {
        for (List<RenderedHop> hops : List.of(
            List.of(hop(link, 403, product("차단 뒤에 숨은 상품", 1), product("차단 뒤에 숨은 상품", 1))),
            List.of(hop(link, 200, CHALLENGE, CHALLENGE), hop(mobile, 429, "", ""))
        )) {
            HeadlessProductLinkExtractor extractor = extractorWith(l -> hops);

            HeadlessRenderException e = assertThrows(HeadlessRenderException.class, () -> extractor.extract(link, false, null));

            assertEquals(ExtractionErrorCode.HEADLESS_BLOCKED, e.code());
            assertFalse(e.permanent());
        }
    }

    @Test
    @DisplayName("챌린지 홉을 지나 실제 상품 홉에 도달했으면 차단이 아니다")
    void challengeThenProductIsNotBlocked() {
        HeadlessProductLinkExtractor extractor = extractorWith(l -> List.of(
            hop(link, 403, CHALLENGE, CHALLENGE),
            hop(mobile, 200, "", product("통과한 상품", 2_000))
        ));

        assertEquals("통과한 상품", extractor.extract(link, false, null).name());
    }

    @Test
    @DisplayName("홉은 있는데 HTML 이 전부 비어 있으면 일시 실패(HEADLESS_UPSTREAM)다")
    void hopsWithoutHtmlAreTransient() {
        HeadlessProductLinkExtractor extractor = extractorWith(l -> List.of(hop(link, 302, "", ""), hop(mobile, 200, "", " ")));

        HeadlessRenderException e = assertThrows(HeadlessRenderException.class, () -> extractor.extract(link, false, null));

        assertEquals(ExtractionErrorCode.HEADLESS_UPSTREAM, e.code());
    }

    @Test
    @DisplayName("렌더 실패는 그대로 전파된다")
    void renderFailurePropagates() {
        HeadlessProductLinkExtractor extractor = extractorWith(l -> {
            throw HeadlessRenderException.upstream("boom", null);
        });

        assertThrows(HeadlessRenderException.class, () -> extractor.extract(link, false, null));
    }
}
