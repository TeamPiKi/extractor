package com.depromeet.piki.extractor.extraction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.depromeet.piki.extractor.common.exception.ExtractionErrorCode;
import com.depromeet.piki.extractor.domain.BlockedDomains;
import com.depromeet.piki.extractor.domain.DeclaredIdentity;
import com.depromeet.piki.extractor.domain.ProductLink;
import com.depromeet.piki.extractor.domain.ProductSnapshot;
import com.depromeet.piki.extractor.domain.ProductSnapshotException;
import com.depromeet.piki.extractor.extraction.gemini.GeminiApiException;
import com.depromeet.piki.extractor.extraction.gemini.GeminiExtractionResult;
import com.depromeet.piki.extractor.extraction.gemini.GeminiOriginalPriceResult;
import com.depromeet.piki.extractor.extraction.http.PageFetchException;
import com.depromeet.piki.extractor.extraction.http.SiteConcurrencyLimiter;
import com.depromeet.piki.extractor.extraction.structured.StructuredDataExtractor;
import com.depromeet.piki.extractor.support.StubGeminiClient;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
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

    private static final String PRODUCT_JSON_LD = "<script type=\"application/ld+json\">"
        + "{\"@type\":\"Product\",\"name\":\"상품\",\"image\":\"https://cdn.example.com/p.png\","
        + "\"offers\":{\"price\":\"9500\",\"priceCurrency\":\"KRW\"}}</script>";
    private static final String PRICE_HIDDEN_HTML = "<html><head>" + PRODUCT_JSON_LD + "</head>"
        + "<body>가격 표시는 스크립트가 그린다</body></html>";
    private static final String PRICE_VISIBLE_HTML = "<html><head>" + PRODUCT_JSON_LD + "</head>"
        + "<body>9,500원</body></html>";

    private final ProductLink link = ProductLink.parse("https://store.kakao.com/kgcmall/products/445653929");

    private final StubGeminiClient stubGemini = new StubGeminiClient();

    private DefaultProductLinkExtractor extractorFetching(String html) {
        PageFetcher fetcher = (l, identity, blockedDomains) -> PageContent.of(l, html);
        return new DefaultProductLinkExtractor(
            fetcher,
            new HtmlSnapshotPipeline(
                new StructuredDataExtractor(new ObjectMapper()),
                new GeminiHtmlExtractor(stubGemini),
                new SimpleMeterRegistry()
            ),
            new SiteConcurrencyLimiter(2, Duration.ofSeconds(15))
        );
    }

    @Test
    @DisplayName("CSR 셸은 LLM 호출 없이 escalatable EMPTY_SHELL 로 재분류한다")
    void reclassifiesNoDataOnEmptyShell() {
        // 게이트(LlmInputGate)가 LLM 전에 끊으므로 stub 세팅이 없어야 정상 — LLM 이 불리면 default throw 로 드러난다.
        PageFetchException e = assertThrows(
            PageFetchException.class,
            () -> extractorFetching(SHELL_HTML).extract(link, false, null, DeclaredIdentity.DEFAULT, BlockedDomains.NONE)
        );

        assertEquals(ExtractionErrorCode.EMPTY_SHELL, e.code());
        assertTrue(e.escalatable());
        assertFalse(e.permanent());
        // 원래의 파싱 실패를 cause 로 보존한다 — 에스컬레이션 후에도 최초 사유를 로그에서 추적할 수 있어야 한다.
        assertInstanceOf(ProductSnapshotException.class, e.getCause());
        assertEquals(0, stubGemini.invocations());
    }

    @Test
    @DisplayName("본문 텍스트가 충분한 페이지의 no-data 는 NOT_PRODUCT_PAGE 그대로 전파한다")
    void propagatesNoDataOnContentRichPage() {
        stubGemini.build = request -> new GeminiExtractionResult(false, null, null, null, null, null);

        ProductSnapshotException e = assertThrows(
            ProductSnapshotException.class,
            () -> extractorFetching(CONTENT_HTML).extract(link, false, null, DeclaredIdentity.DEFAULT, BlockedDomains.NONE)
        );

        assertEquals(ExtractionErrorCode.NOT_PRODUCT_PAGE, e.code());
    }

    @Test
    @DisplayName("판매가가 본문에 없고 정가를 모르면 LLM 을 묻지 않고 plain 결과를 실어 렌더 승격을 제안한다")
    void proposesRenderWithoutAskingLlmWhenPriceIsNotVisible() {
        // stub 세팅이 없어야 정상 — 정가 LLM 이 렌더보다 먼저 불리면 default throw 로 드러난다.
        OriginalPriceHiddenException e = assertThrows(
            OriginalPriceHiddenException.class,
            () -> extractorFetching(PRICE_HIDDEN_HTML).extract(link, false, null, DeclaredIdentity.DEFAULT, BlockedDomains.NONE)
        );

        assertEquals(9_500, e.plainSnapshot().currentPrice());
        assertNull(e.plainSnapshot().originalPrice());
        assertEquals(0, stubGemini.invocations());
    }

    @Test
    @DisplayName("미뤄 둔 정가 LLM 은 마지막 수단으로 불렸을 때 plain 문서로 묻는다")
    void lastResortAsksDeferredLlm() {
        OriginalPriceHiddenException e = assertThrows(
            OriginalPriceHiddenException.class,
            () -> extractorFetching(PRICE_HIDDEN_HTML).extract(link, false, null, DeclaredIdentity.DEFAULT, BlockedDomains.NONE)
        );
        stubGemini.build = request -> new GeminiOriginalPriceResult(12_500);

        assertEquals(12_500, e.lastResort().originalPrice());
        assertEquals(1, stubGemini.invocations());
    }

    @Test
    @DisplayName("마지막 수단의 정가 LLM 이 실패해도 plain 결과는 유지된다")
    void lastResortKeepsSnapshotWhenLlmFails() {
        OriginalPriceHiddenException e = assertThrows(
            OriginalPriceHiddenException.class,
            () -> extractorFetching(PRICE_HIDDEN_HTML).extract(link, false, null, DeclaredIdentity.DEFAULT, BlockedDomains.NONE)
        );
        stubGemini.build = request -> {
            throw GeminiApiException.upstreamError(new RuntimeException("gemini 503"));
        };

        ProductSnapshot snapshot = e.lastResort();

        assertEquals(9_500, snapshot.currentPrice());
        assertNull(snapshot.originalPrice());
    }

    @Test
    @DisplayName("LLM 추출로 끝난 결과도 판매가가 본문에 없고 정가를 모르면 승격을 제안하되 LLM 을 다시 묻지 않는다")
    void proposesRenderOnLlmPathWithoutSecondLlmCall() {
        String dataIslandOnly = "<html><head><script type=\"application/json\">"
            + "{\"product\":{\"name\":\"상품\",\"price\":9500}}</script></head>"
            + "<body>" + "가격 표시는 스크립트가 그리는 상품 설명 본문. ".repeat(20) + "</body></html>";
        stubGemini.build = request ->
            new GeminiExtractionResult(true, "상품", 9_500, null, "KRW", "https://cdn.example.com/p.png");

        OriginalPriceHiddenException e = assertThrows(
            OriginalPriceHiddenException.class,
            () -> extractorFetching(dataIslandOnly).extract(link, false, null, DeclaredIdentity.DEFAULT, BlockedDomains.NONE)
        );

        assertEquals(e.plainSnapshot(), e.lastResort());
        assertEquals(1, stubGemini.invocations());
    }

    @Test
    @DisplayName("판매가가 본문에 보이면 정가를 못 찾아도 승격을 제안하지 않는다")
    void doesNotProposeRenderWhenPriceIsVisible() {
        stubGemini.build = request -> new GeminiOriginalPriceResult(null);

        assertNull(
            extractorFetching(PRICE_VISIBLE_HTML).extract(link, false, null, DeclaredIdentity.DEFAULT, BlockedDomains.NONE).originalPrice()
        );
    }

    @Test
    @DisplayName("LLM 일시 오류는 셸 수준 페이지여도 재분류하지 않고 그대로 전파한다")
    void propagatesLlmUpstreamFailureUntouched() {
        // 데이터 script 가 있어 게이트는 통과하되(LLM 이 실제로 불린다) EmptyShellDetector 기준으로는 여전히
        // 셸인 페이지 — 재분류 catch 가 GeminiApiException 을 EMPTY_SHELL 로 오염시키지 않는지가 관심사다.
        String dataIslandShell = "<html><head><script type=\"application/json\">"
            + "{\"product\":{\"name\":\"상품\"}}</script></head><body><div id=\"app\"></div></body></html>";
        stubGemini.build = request -> {
            throw GeminiApiException.upstreamError(new RuntimeException("gemini 503"));
        };

        assertThrows(GeminiApiException.class, () -> extractorFetching(dataIslandShell).extract(link, false, null, DeclaredIdentity.DEFAULT, BlockedDomains.NONE));
    }
}
