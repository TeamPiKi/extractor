package com.depromeet.piki.extractor.extraction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.depromeet.piki.extractor.domain.ExtractionMethod;
import com.depromeet.piki.extractor.domain.ProductLink;
import com.depromeet.piki.extractor.domain.ProductSnapshot;
import com.depromeet.piki.extractor.extraction.gemini.GeminiApiException;
import com.depromeet.piki.extractor.extraction.gemini.GeminiExtractionRequest;
import com.depromeet.piki.extractor.extraction.gemini.GeminiOriginalPriceResult;
import com.depromeet.piki.extractor.extraction.structured.StructuredDataExtractor;
import com.depromeet.piki.extractor.support.StubGeminiClient;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * 구조화 성공 뒤의 정가 보강 — "판매가만 찾았으면 정가를 LLM 에 묻고, 그 결과가 무엇이든 구조화 성공은 유지된다".
 * 구조화 파서가 정가 태그를 읽는 분기는 {@code StructuredDataExtractorTest}, 신뢰 규칙은
 * {@code ProductSnapshotTest} 가 진다.
 */
class HtmlSnapshotPipelineOriginalPriceTest {

    private static final String PRICE_ONLY_HTML = "<html><head><script type=\"application/ld+json\">"
        + "{\"@type\":\"Product\",\"name\":\"판매가만 있는 상품\",\"image\":\"https://cdn.example.com/p.png\","
        + "\"offers\":{\"price\":\"9500\",\"priceCurrency\":\"KRW\"}}"
        + "</script></head><body><s>12,500원</s> 9,500원</body></html>";

    private static final String WITH_ORIGINAL_HTML = "<html><head>"
        + "<meta property=\"product:price:amount\" content=\"27900\"/>"
        + "<meta property=\"product:price:currency\" content=\"KRW\"/>"
        + "<meta property=\"product:price:normal_price\" content=\"36200\"/>"
        + "<meta property=\"og:title\" content=\"정가 태그가 있는 상품\"/>"
        + "<meta property=\"og:image\" content=\"https://cdn.example.com/p.png\"/>"
        + "</head><body></body></html>";

    private final ProductLink link = ProductLink.parse("https://m.a-bly.com/goods/2242934");

    private final StubGeminiClient stubGemini = new StubGeminiClient();

    private HtmlSnapshotPipeline pipeline() {
        return new HtmlSnapshotPipeline(
            new StructuredDataExtractor(new ObjectMapper()),
            new GeminiHtmlExtractor(stubGemini),
            new SimpleMeterRegistry()
        );
    }

    @Test
    @DisplayName("구조화 결과에 정가가 없으면 정가만 묻는 LLM 호출로 채우고 method 는 STRUCTURED 로 남는다")
    void fillsOriginalPriceFromLlm() {
        stubGemini.build = request -> new GeminiOriginalPriceResult(12_500);

        ProductSnapshot snapshot = pipeline().extract(PageContent.of(link, PRICE_ONLY_HTML), "fetch=0ms", null);

        assertEquals(9_500, snapshot.currentPrice());
        assertEquals(12_500, snapshot.originalPrice());
        assertEquals(ExtractionMethod.STRUCTURED, snapshot.method());
        assertEquals(1, stubGemini.invocations());
        GeminiExtractionRequest sent = assertInstanceOf(GeminiExtractionRequest.class, stubGemini.lastRequest());
        assertTrue(sent.generationConfig().responseJsonSchema().properties().containsKey("originalPrice"));
        assertTrue(sent.contents().getFirst().parts().getLast().text().contains("CURRENT PRICE: 9500"));
    }

    @Test
    @DisplayName("정가 태그를 이미 읽었으면 LLM 을 부르지 않는다")
    void skipsLlmWhenStructuredHasOriginalPrice() {
        ProductSnapshot snapshot = pipeline().extract(PageContent.of(link, WITH_ORIGINAL_HTML), "fetch=0ms", null);

        assertEquals(36_200, snapshot.originalPrice());
        assertEquals(0, stubGemini.invocations());
    }

    @Test
    @DisplayName("보강 LLM 호출이 실패하면 정가만 모름으로 두고 구조화 성공은 그대로 돌려준다")
    void llmFailureKeepsStructuredSuccess() {
        stubGemini.build = request -> {
            throw GeminiApiException.upstreamError(null);
        };

        ProductSnapshot snapshot = pipeline().extract(PageContent.of(link, PRICE_ONLY_HTML), "fetch=0ms", null);

        assertEquals(9_500, snapshot.currentPrice());
        assertNull(snapshot.originalPrice());
        assertEquals(ExtractionMethod.STRUCTURED, snapshot.method());
    }

    @Test
    @DisplayName("LLM 이 정가 근거 없음(null)을 돌려주면 모름으로 둔다")
    void nullAnswerIsUnknown() {
        stubGemini.build = request -> new GeminiOriginalPriceResult(null);

        assertNull(pipeline().extract(PageContent.of(link, PRICE_ONLY_HTML), "fetch=0ms", null).originalPrice());
        assertEquals(1, stubGemini.invocations());
    }

    @Test
    @DisplayName("호출자가 지정한 모델이 보강 호출까지 전달된다")
    void modelReachesEnrichment() {
        stubGemini.build = request -> new GeminiOriginalPriceResult(12_500);

        pipeline().extract(PageContent.of(link, PRICE_ONLY_HTML), "fetch=0ms", "gemini-test-model");

        assertEquals("gemini-test-model", stubGemini.lastModel());
    }

    @Test
    @DisplayName("LLM 이 판매가보다 낮은 정가를 돌려주면 모름으로 내린다")
    void belowCurrentPriceIsUnknown() {
        stubGemini.build = request -> new GeminiOriginalPriceResult(9_000);

        assertNull(pipeline().extract(PageContent.of(link, PRICE_ONLY_HTML), "fetch=0ms", null).originalPrice());
    }

    @Test
    @DisplayName("호출자 기한이 지났으면 정가 보강을 건너뛰고 구조화 성공을 돌려준다")
    void passedDeadlineSkipsEnrichment() {
        ProductSnapshot snapshot = ExtractionDeadline.within(
            Duration.ZERO, () -> pipeline().extract(PageContent.of(link, PRICE_ONLY_HTML), "fetch=0ms", null));

        assertEquals(9_500, snapshot.currentPrice());
        assertNull(snapshot.originalPrice());
        assertEquals(0, stubGemini.invocations());
    }
}
