package com.depromeet.piki.extractor.extraction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.depromeet.piki.extractor.common.exception.ExtractionErrorCode;
import com.depromeet.piki.extractor.domain.ProductLink;
import com.depromeet.piki.extractor.domain.ProductSnapshotException;
import com.depromeet.piki.extractor.extraction.gemini.GeminiApiException;
import com.depromeet.piki.extractor.extraction.gemini.GeminiExtractionResult;
import com.depromeet.piki.extractor.extraction.http.PageFetchException;
import com.depromeet.piki.extractor.extraction.structured.StructuredDataExtractor;
import com.depromeet.piki.extractor.support.StubGeminiClient;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * plain 전략의 실패 재분류 — "파싱 no-data + CSR 셸" 만 escalatable(EMPTY_SHELL)로 바꾸고, 나머지 실패는
 * 원형 그대로 전파하는지 본다. 재분류 이후의 라우팅(escalatable → 헤드리스)은 FallbackProductLinkExtractorTest 가,
 * 셸 판정 분기 망라는 EmptyShellDetectorTest 가 진다. 외부 경계(PageFetcher·GeminiClient)만 fake/stub.
 */
class DefaultProductLinkExtractorTest {

    private static final String SHELL_HTML = "<html><head><script src=\"/app.js\"></script></head>"
        + "<body><div id=\"app\"></div></body></html>";
    private static final String CONTENT_HTML = "<html><body><article>"
        + "상품이 아닌 콘텐츠 페이지의 충분히 긴 본문 텍스트. ".repeat(20)
        + "</article></body></html>";

    private final ProductLink link = ProductLink.parse("https://store.kakao.com/kgcmall/products/445653929");

    private final StubGeminiClient stubGemini = new StubGeminiClient();

    private DefaultProductLinkExtractor extractorFetching(String html) {
        PageFetcher fetcher = l -> PageContent.of(l, html);
        return new DefaultProductLinkExtractor(
            fetcher,
            new HtmlSnapshotPipeline(
                new StructuredDataExtractor(new ObjectMapper()),
                new GeminiHtmlExtractor(stubGemini),
                new SimpleMeterRegistry()
            )
        );
    }

    @Test
    @DisplayName("파싱 no-data 인데 본문이 CSR 셸이면 escalatable EMPTY_SHELL 로 재분류한다")
    void reclassifiesNoDataOnEmptyShell() {
        stubGemini.build = request -> new GeminiExtractionResult(false, null, null, null, null);

        PageFetchException e = assertThrows(
            PageFetchException.class,
            () -> extractorFetching(SHELL_HTML).extract(link, null)
        );

        assertEquals(ExtractionErrorCode.EMPTY_SHELL, e.code());
        assertTrue(e.escalatable());
        assertTrue(e.permanent());
        // 원래의 파싱 실패를 cause 로 보존한다 — 에스컬레이션 후에도 최초 사유를 로그에서 추적할 수 있어야 한다.
        assertInstanceOf(ProductSnapshotException.class, e.getCause());
    }

    @Test
    @DisplayName("본문 텍스트가 충분한 페이지의 no-data 는 NOT_PRODUCT_PAGE 그대로 전파한다")
    void propagatesNoDataOnContentRichPage() {
        stubGemini.build = request -> new GeminiExtractionResult(false, null, null, null, null);

        ProductSnapshotException e = assertThrows(
            ProductSnapshotException.class,
            () -> extractorFetching(CONTENT_HTML).extract(link, null)
        );

        assertEquals(ExtractionErrorCode.NOT_PRODUCT_PAGE, e.code());
    }

    @Test
    @DisplayName("LLM 일시 오류는 셸 페이지여도 재분류하지 않고 그대로 전파한다")
    void propagatesLlmUpstreamFailureUntouched() {
        stubGemini.build = request -> {
            throw GeminiApiException.upstreamError(new RuntimeException("gemini 503"));
        };

        assertThrows(GeminiApiException.class, () -> extractorFetching(SHELL_HTML).extract(link, null));
    }
}
