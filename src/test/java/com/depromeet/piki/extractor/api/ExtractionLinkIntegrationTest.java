package com.depromeet.piki.extractor.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.depromeet.piki.extractor.extraction.PageContent;
import com.depromeet.piki.extractor.extraction.gemini.GeminiExtractionResult;
import com.depromeet.piki.extractor.extraction.http.PageFetchException;
import com.depromeet.piki.extractor.support.IntegrationTestSupport;
import com.depromeet.piki.extractor.support.StubGeminiClient;
import com.depromeet.piki.extractor.support.StubPageFetcher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

// POST /internal/extractions/link 의 HTTP 계약(docs/api-contract.md 응답 3갈래)을 실제 파이프라인
// (Fallback→plain→구조화→LLM fallback, 외부 경계 PageFetcher·GeminiClient 만 stub)으로 검증한다.
// 호출자(PIKI-Server)의 전이 판정이 status 만 보므로, status 와 body 모양(code·필드)이 이 테스트의 계약이다.
class ExtractionLinkIntegrationTest extends IntegrationTestSupport {

    private static final String STRUCTURED_HTML = """
        <html><head><script type="application/ld+json">
        {"@type":"Product","name":"직접파싱 상품","image":"https://cdn.example.com/p.png",
         "offers":{"price":"39000","priceCurrency":"KRW"}}
        </script></head><body></body></html>
        """;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private StubPageFetcher stubPageFetcher;

    @Autowired
    private StubGeminiClient stubGeminiClient;

    private MockMvc mockMvc() {
        return MockMvcBuilders.webAppContextSetup(context).build();
    }

    private String body(String url) {
        return "{\"url\": \"" + url + "\"}";
    }

    @Test
    @DisplayName("구조화 데이터가 충분하면 LLM 없이 200 과 필수 필드를 반환한다")
    void structuredSuccess() throws Exception {
        stubGeminiClient.reset();
        stubPageFetcher.build = link -> PageContent.of(link, STRUCTURED_HTML);

        mockMvc().perform(post("/internal/extractions/link")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Correlation-Id", "snapshot-1")
                .content(body("https://shop.example.com/p/1")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("직접파싱 상품"))
            .andExpect(jsonPath("$.imageUrl").value("https://cdn.example.com/p.png"))
            .andExpect(jsonPath("$.currentPrice").value(39000))
            .andExpect(jsonPath("$.currency").value("KRW"));

        if (stubGeminiClient.invocations() != 0) {
            throw new AssertionError("구조화 파싱 성공 시 LLM 을 호출하면 안 된다");
        }
    }

    @Test
    @DisplayName("구조화 데이터가 없으면 LLM fallback 으로 추출해 200 을 반환한다")
    void llmFallbackSuccess() throws Exception {
        stubGeminiClient.reset();
        stubPageFetcher.build = link -> PageContent.of(link, "<html><body>구조화 없음</body></html>");
        stubGeminiClient.build = request ->
            new GeminiExtractionResult(true, "엘엘엠 상품", 12000, "KRW", "https://cdn.example.com/llm.png");

        mockMvc().perform(post("/internal/extractions/link")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("https://shop.example.com/p/2")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("엘엘엠 상품"))
            .andExpect(jsonPath("$.currentPrice").value(12000));
    }

    @Test
    @DisplayName("LLM 이 상품 페이지가 아니라고 판정하면 422 NOT_PRODUCT_PAGE 를 반환한다")
    void notProductPage() throws Exception {
        stubGeminiClient.reset();
        stubPageFetcher.build = link -> PageContent.of(link, "<html><body>블로그 글</body></html>");
        stubGeminiClient.build = request -> new GeminiExtractionResult(false, null, null, null, null);

        mockMvc().perform(post("/internal/extractions/link")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("https://blog.example.com/post/3")))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("NOT_PRODUCT_PAGE"));
    }

    @Test
    @DisplayName("추출 결과가 READY 필수 필드(name·price·imageUrl)를 못 채우면 422 UNTRUSTWORTHY_VALUE 를 반환한다")
    void incompleteExtraction() throws Exception {
        stubGeminiClient.reset();
        stubPageFetcher.build = link -> PageContent.of(link, "<html><body>구조화 없음</body></html>");
        // imageUrl 없음 — 호출자 READY 불변식(name·price·imageUrl)을 못 채우므로 성공으로 내리면 안 된다.
        stubGeminiClient.build = request -> new GeminiExtractionResult(true, "이미지 없는 상품", 5000, "KRW", null);

        mockMvc().perform(post("/internal/extractions/link")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("https://shop.example.com/p/4")))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("UNTRUSTWORTHY_VALUE"));
    }

    @Test
    @DisplayName("대상 몰이 4xx 로 막으면 422 FETCH_CLIENT_ERROR 를 반환한다")
    void fetchClientError() throws Exception {
        stubGeminiClient.reset();
        stubPageFetcher.build = link -> {
            throw PageFetchException.clientError(new RuntimeException("403"));
        };

        mockMvc().perform(post("/internal/extractions/link")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("https://blocked.example.com/p/5")))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("FETCH_CLIENT_ERROR"));
    }

    @Test
    @DisplayName("대상 몰이 일시 오류(5xx 게이트웨이)면 502 UPSTREAM_ERROR 를 반환한다 (호출자가 재시도)")
    void transientUpstreamError() throws Exception {
        stubGeminiClient.reset();
        stubPageFetcher.build = link -> {
            throw PageFetchException.upstreamError(new RuntimeException("503"));
        };

        mockMvc().perform(post("/internal/extractions/link")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("https://busy.example.com/p/6")))
            .andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.code").value("UPSTREAM_ERROR"));
    }

    @Test
    @DisplayName("url 이 형식 위반이면 422 INVALID_URL 을 반환한다 (호출자 동기 검증의 방어선)")
    void invalidUrl() throws Exception {
        mockMvc().perform(post("/internal/extractions/link")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("not-a-url")))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("INVALID_URL"));
    }
}
