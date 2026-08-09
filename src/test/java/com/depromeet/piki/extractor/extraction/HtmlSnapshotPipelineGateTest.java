package com.depromeet.piki.extractor.extraction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.depromeet.piki.extractor.common.exception.ExtractionErrorCode;
import com.depromeet.piki.extractor.domain.ProductLink;
import com.depromeet.piki.extractor.domain.ProductSnapshot;
import com.depromeet.piki.extractor.domain.ProductSnapshotException;
import com.depromeet.piki.extractor.extraction.gemini.GeminiExtractionResult;
import com.depromeet.piki.extractor.extraction.structured.StructuredDataExtractor;
import com.depromeet.piki.extractor.support.StubGeminiClient;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * 파이프라인의 게이트 라우팅 — "볼 게 없는 문서는 LLM 을 부르지 않고 확정 실패, 볼 게 있으면 기존 LLM
 * fallback" 을 검증한다. 게이트 판정 자체의 분기 망라는 {@link LlmInputGateTest} 가, Prometheus 라벨 키
 * 정합은 {@link HtmlSnapshotPipelineMetricTest} 가 진다. 외부 경계 GeminiClient 만 stub.
 */
class HtmlSnapshotPipelineGateTest {

    private static final String SHELL_HTML =
        "<html><head><script src=\"/app.js\"></script></head><body><div id=\"root\"></div></body></html>";

    private final ProductLink link = ProductLink.parse("https://mobile.a-bly.com/goods/70580148");

    private final StubGeminiClient stubGemini = new StubGeminiClient();

    private HtmlSnapshotPipeline pipeline(SimpleMeterRegistry registry) {
        return new HtmlSnapshotPipeline(
            new StructuredDataExtractor(new ObjectMapper()),
            new GeminiHtmlExtractor(stubGemini),
            registry
        );
    }

    @Test
    @DisplayName("볼 게 없는 셸은 LLM 호출 없이 NO_EXTRACTABLE_CONTENT 확정 실패로 끊는다")
    void shellSkipsLlmAndFailsPermanently() {
        // stub 의 default build 는 throw(IllegalStateException) — 게이트가 새면 예외 타입부터 달라져 드러난다.
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        ProductSnapshotException e = assertThrows(
            ProductSnapshotException.class,
            () -> pipeline(registry).extract(PageContent.of(link, SHELL_HTML), "fetch=0ms", null)
        );

        assertEquals(ExtractionErrorCode.NO_EXTRACTABLE_CONTENT, e.code());
        assertEquals(0, stubGemini.invocations());
    }

    @Test
    @DisplayName("게이트 발동 건은 via=skipped_shell 로만 집계되고 via=llm 은 오르지 않는다")
    void gateCountsAsSkippedShellNotLlm() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        assertThrows(
            ProductSnapshotException.class,
            () -> pipeline(registry).extract(PageContent.of(link, SHELL_HTML), "fetch=0ms", null)
        );

        assertEquals(
            1.0,
            registry.counter("product.extract", "via", "skipped_shell", "reason", "no_data").count()
        );
        assertEquals(0.0, registry.counter("product.extract", "via", "llm", "reason", "no_data").count());
    }

    @Test
    @DisplayName("가시 텍스트가 없어도 데이터 script 가 있으면 LLM fallback 이 그대로 돈다")
    void dataIslandStillReachesLlm() {
        // 구조화 파서가 못 읽는 모양의 JSON(비 schema.org)이라 Miss 로 내려가고, 게이트는 데이터 script 존재로 통과.
        String hydrationShell = "<html><head><script id=\"__NEXT_DATA__\" type=\"application/json\">"
            + "{\"props\":{\"product\":{\"name\":\"하이드레이션 상품\",\"price\":12000}}}"
            + "</script></head><body><div id=\"__next\"></div></body></html>";
        stubGemini.build = request ->
            new GeminiExtractionResult(true, "하이드레이션 상품", 12_000, "KRW", "https://cdn.example.com/p.png");

        ProductSnapshot snapshot = pipeline(new SimpleMeterRegistry())
            .extract(PageContent.of(link, hydrationShell), "fetch=0ms", null);

        assertEquals("하이드레이션 상품", snapshot.name());
        assertEquals(1, stubGemini.invocations());
    }
}
