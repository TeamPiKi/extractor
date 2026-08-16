package com.depromeet.piki.extractor.extraction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.depromeet.piki.extractor.common.exception.ExtractionErrorCode;
import com.depromeet.piki.extractor.domain.ProductLink;
import com.depromeet.piki.extractor.domain.ProductSnapshot;
import com.depromeet.piki.extractor.domain.ProductSnapshotException;
import com.depromeet.piki.extractor.extraction.gemini.GeminiExtractionResult;
import com.depromeet.piki.extractor.extraction.headless.HeadlessRenderException;
import com.depromeet.piki.extractor.extraction.headless.HeadlessRenderer;
import com.depromeet.piki.extractor.extraction.structured.StructuredDataExtractor;
import com.depromeet.piki.extractor.support.StubGeminiClient;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * 헤드리스 전략의 조립 — 렌더된 HTML 이 plain 과 동일한 파이프라인(구조화 우선 → LLM fallback)으로 흐르는지 본다.
 *
 * <p>렌더 자체의 wire·verdict 번역은 HttpHeadlessRendererTest 가, 직행/에스컬레이션 라우팅은
 * FallbackProductLinkExtractorTest 가 진다. 외부 경계(HeadlessRenderer·GeminiClient)만 fake/stub.
 */
class HeadlessProductLinkExtractorTest {

    private final ProductLink link = ProductLink.parse("https://kream.co.kr/products/6963");

    private final StubGeminiClient stubGemini = new StubGeminiClient();

    private HeadlessProductLinkExtractor extractorWith(Function<ProductLink, PageContent> render) {
        HeadlessRenderer renderer = (target, authorized) -> render.apply(target);
        return new HeadlessProductLinkExtractor(
            renderer,
            new HtmlSnapshotPipeline(
                new StructuredDataExtractor(new ObjectMapper()),
                new GeminiHtmlExtractor(stubGemini),
                new SimpleMeterRegistry()
            )
        );
    }

    @Test
    @DisplayName("렌더된 HTML 의 구조화 데이터로 LLM 없이 스냅샷을 만든다")
    void structuredDataFromRenderedHtml() {
        String html = "<html><head><script type=\"application/ld+json\">"
            + "{\"@type\":\"Product\",\"name\":\"렌더 상품\",\"image\":\"https://cdn.example.com/p.png\","
            + "\"offers\":{\"price\":\"209000\",\"priceCurrency\":\"KRW\"}}"
            + "</script></head><body></body></html>";
        HeadlessProductLinkExtractor extractor = extractorWith(l -> PageContent.of(l, html));

        ProductSnapshot snapshot = extractor.extract(link, false, null);

        assertEquals("렌더 상품", snapshot.name());
        assertEquals(209_000, snapshot.currentPrice());
        assertEquals(0, stubGemini.invocations());
    }

    @Test
    @DisplayName("렌더된 HTML 에 구조화 데이터가 없으면 같은 HTML 로 LLM fallback 을 탄다")
    void llmFallbackOnRenderedHtml() {
        stubGemini.build = request -> new GeminiExtractionResult(true, "엘엘엠 상품", 50_000, "KRW", "https://cdn.example.com/i.png");
        HeadlessProductLinkExtractor extractor = extractorWith(l -> PageContent.of(
            l,
            "<html><body>" + "구조화 데이터 없이 렌더된 상품 상세 설명 텍스트. ".repeat(3) + "</body></html>"
        ));

        ProductSnapshot snapshot = extractor.extract(link, false, null);

        assertEquals("엘엘엠 상품", snapshot.name());
        assertEquals(1, stubGemini.invocations());
    }

    @Test
    @DisplayName("렌더 결과까지 셸이면 LLM 호출 없이 NO_EXTRACTABLE_CONTENT 가 그대로 전파된다 — plain 과 달리 재분류가 없다")
    void renderedShellFailsPermanentlyWithoutLlm() {
        // 헤드리스는 마지막 수단이라 셸 재분류(escalation)가 없다 — 게이트의 확정 실패가 곧 최종 응답이 된다.
        // 에이블리 mobile.* 환각 사고(렌더 후에도 가시 텍스트 0자)가 이 경로로 닫힌다.
        HeadlessProductLinkExtractor extractor = extractorWith(l -> PageContent.of(
            l,
            "<html><head><script src=\"/app.js\"></script></head><body><div id=\"root\"></div></body></html>"
        ));

        ProductSnapshotException e = assertThrows(
            ProductSnapshotException.class,
            () -> extractor.extract(link, false, null)
        );

        assertEquals(ExtractionErrorCode.NO_EXTRACTABLE_CONTENT, e.code());
        assertEquals(0, stubGemini.invocations());
    }

    @Test
    @DisplayName("렌더 실패는 그대로 전파된다 — 계약 번역은 렌더러가 이미 끝냈다")
    void renderFailurePropagates() {
        HeadlessProductLinkExtractor extractor = extractorWith(l -> {
            throw HeadlessRenderException.blocked();
        });

        assertThrows(HeadlessRenderException.class, () -> extractor.extract(link, false, null));
    }
}
