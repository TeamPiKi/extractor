package com.depromeet.piki.extractor.extraction;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.depromeet.piki.extractor.domain.ProductLink;
import com.depromeet.piki.extractor.extraction.gemini.GeminiExtractionResult;
import com.depromeet.piki.extractor.extraction.structured.StructuredDataExtractor;
import com.depromeet.piki.extractor.support.StubGeminiClient;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * product.extract 카운터가 운영 레지스트리(Prometheus)에서 두 경로(structured/llm) 모두 scrape 되는지 검증한다.
 *
 * <p>Prometheus(client 1.x)는 같은 메트릭 이름의 태그 키 집합이 다르면 뒤 시계열을 예외 없이 조용히 드롭하므로,
 * 두 경로의 태그 키가 {@code {via, reason}} 으로 일치해야 둘 다 남는다. 일반 통합 테스트는 이 제약이 없는
 * SimpleMeterRegistry 를 주입해 이 회귀를 못 잡으므로, 운영과 같은 PrometheusMeterRegistry 로 파이프라인을
 * 직접 구성하는 별도 분류로 둔다(외부 경계 GeminiClient 만 stub).
 */
class HtmlSnapshotPipelineMetricTest {

    private final ProductLink link = ProductLink.parse("https://shop.example.com/products/42");

    @Test
    @DisplayName("structured 와 llm 두 경로의 product_extract 시계열이 Prometheus scrape 에 모두 남는다")
    void bothPathsSurviveInPrometheusScrape() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        StubGeminiClient stubGemini = new StubGeminiClient();
        HtmlSnapshotPipeline pipeline = new HtmlSnapshotPipeline(
            new StructuredDataExtractor(new ObjectMapper()),
            new GeminiHtmlExtractor(stubGemini),
            registry
        );

        String structuredHtml = "<html><head><script type=\"application/ld+json\">"
            + "{\"@type\":\"Product\",\"name\":\"직접파싱\",\"offers\":{\"price\":\"1000\"}}"
            + "</script></head><body></body></html>";
        pipeline.extract(PageContent.of(link, structuredHtml), "fetch=0ms", null);

        stubGemini.build = request -> new GeminiExtractionResult(true, "엘엘엠", 2_000, null, null);
        pipeline.extract(PageContent.of(link, "<html><body>구조화 없음</body></html>"), "fetch=0ms", null);

        String scrape = registry.scrape();
        List<String> lines = scrape.lines().filter(l -> l.startsWith("product_extract_total{")).toList();
        // 라벨 출력 순서는 Prometheus 가 정렬하므로(via/reason 순서 비보장) 부분 문자열로 단언한다.
        assertTrue(
            lines.stream().anyMatch(l -> l.contains("via=\"structured\"") && l.contains("reason=\"none\"")),
            "structured 시계열(reason=none)이 scrape 에 있어야 한다:\n" + scrape
        );
        assertTrue(
            lines.stream().anyMatch(l -> l.contains("via=\"llm\"")),
            "llm 시계열이 scrape 에 있어야 한다 — 태그 키 불일치로 드롭되면 안 된다:\n" + scrape
        );
    }
}
