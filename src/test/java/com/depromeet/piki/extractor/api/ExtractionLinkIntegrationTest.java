package com.depromeet.piki.extractor.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.depromeet.piki.extractor.domain.ProductLink;
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

/**
 * {@code POST /internal/extractions/link} 의 HTTP 계약(docs/api-contract.md 응답 3갈래)을 실제 파이프라인
 * (Fallback→plain→구조화→LLM fallback)으로 검증한다. 외부 경계인 PageFetcher·GeminiClient 만 stub 이다.
 *
 * <p>호출자(core)의 전이 판정이 status 만 보므로, status 와 body 모양(code·필드)이 이 테스트의 계약이다.
 */
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
            .andExpect(jsonPath("$.currency").value("KRW"))
            // additive 계약(core#825): 리다이렉트 없으면 finalUrl == 요청 원본, 구조화 경로는 method=STRUCTURED.
            .andExpect(jsonPath("$.finalUrl").value("https://shop.example.com/p/1"))
            .andExpect(jsonPath("$.method").value("STRUCTURED"));

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
            .andExpect(jsonPath("$.currentPrice").value(12000))
            // LLM 분기도 withOrigin 을 거친다 — finalUrl 전달 회귀를 method 단언만으로는 못 잡는다.
            .andExpect(jsonPath("$.finalUrl").value("https://shop.example.com/p/2"))
            .andExpect(jsonPath("$.method").value("LLM"));
    }

    @Test
    @DisplayName("리다이렉트를 따라갔으면 finalUrl 이 귀결점을 담는다 - 단축링크 정체성 정규화의 입력")
    void finalUrlReflectsRedirectDestination() throws Exception {
        stubGeminiClient.reset();
        // 단축링크가 정식 상품 페이지로 리다이렉트된 상황 — fetcher 가 최종 URL 을 함께 돌려준다.
        stubPageFetcher.build = link -> new PageContent(
            link,
            STRUCTURED_HTML,
            ProductLink.parse("https://real-shop.example.com/products/42")
        );

        mockMvc().perform(post("/internal/extractions/link")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("https://short.example.com/abc123")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.finalUrl").value("https://real-shop.example.com/products/42"))
            .andExpect(jsonPath("$.method").value("STRUCTURED"));
    }

    @Test
    @DisplayName("headlessFirst 힌트는 headless 스위치가 꺼진 기본 구성에서 무시되고 plain 경로로 정상 추출한다")
    void headlessFirstHintIgnoredWhenHeadlessDisabled() throws Exception {
        // 힌트를 안 보내는 구버전 호출자의 하위호환은 이 클래스의 나머지 케이스 전부가 url 만 보내며 상시 검증한다.
        stubGeminiClient.reset();
        stubPageFetcher.build = link -> PageContent.of(link, STRUCTURED_HTML);

        mockMvc().perform(post("/internal/extractions/link")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\": \"https://shop.example.com/p/9\", \"headlessFirst\": true}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("직접파싱 상품"));
    }

    @Test
    @DisplayName("LLM 이 상품 페이지가 아니라고 판정하면 422 NOT_PRODUCT_PAGE 를 반환한다")
    void notProductPage() throws Exception {
        stubGeminiClient.reset();
        // 본문 텍스트가 충분한 진짜 콘텐츠 페이지 — CSR 셸 재분류(EMPTY_SHELL)에 걸리지 않고 no-data 원형이 나간다.
        stubPageFetcher.build = link ->
            PageContent.of(link, "<html><body><article>" + "상품이 아닌 블로그 글의 충분히 긴 본문. ".repeat(20) + "</article></body></html>");
        stubGeminiClient.build = request -> new GeminiExtractionResult(false, null, null, null, null);

        mockMvc().perform(post("/internal/extractions/link")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("https://blog.example.com/post/3")))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("NOT_PRODUCT_PAGE"));
    }

    @Test
    @DisplayName("파싱 no-data 인데 본문이 CSR 셸이면 422 EMPTY_SHELL 로 재분류된다 (헤드리스 꺼진 구성의 표면 계약)")
    void emptyShellReclassified() throws Exception {
        stubGeminiClient.reset();
        // 카카오 톡딜 store.kakao.com 실측 축약형 — script 뿐인 SPA 셸. 헤드리스가 켜진 구성이면 escalatable 이라
        // 헤드리스 결과가 대신 응답되고, 꺼진 기본 구성(이 컨텍스트)에선 EMPTY_SHELL 이 그대로 표면화된다.
        stubPageFetcher.build = link ->
            PageContent.of(link, "<html><head><script src=\"/app.js\"></script></head><body><div id=\"app\"></div></body></html>");
        stubGeminiClient.build = request -> new GeminiExtractionResult(false, null, null, null, null);

        mockMvc().perform(post("/internal/extractions/link")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("https://store.kakao.com/kgcmall/products/445653929")))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("EMPTY_SHELL"));
    }

    @Test
    @DisplayName("추출 결과가 READY 필수 필드(name·price·imageUrl)를 못 채우면 422 UNTRUSTWORTHY_VALUE 를 반환한다")
    void incompleteExtraction() throws Exception {
        stubGeminiClient.reset();
        stubPageFetcher.build = link -> PageContent.of(link, "<html><body>구조화 없음</body></html>");
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

    /**
     * 요청에 실린 모델이 진입점부터 LLM 호출까지 흘러야 한다. 중간 층(전략·파이프라인) 어디서든 끊기면 호출자가
     * 백오피스에서 모델을 바꿔도 조용히 기본 모델로 도는데, 그건 응답만 봐서는 드러나지 않는다.
     */
    @Test
    @DisplayName("요청에 실린 model 이 LLM 호출까지 전달된다")
    void passesModelHint() throws Exception {
        stubGeminiClient.reset();
        stubGeminiClient.build = request -> new GeminiExtractionResult(
            true, "LLM 상품", 12_000, "KRW", "https://cdn.example.com/llm.png");
        stubPageFetcher.build = link -> PageContent.of(link, "<html><body>구조화 없음</body></html>");

        mockMvc().perform(post("/internal/extractions/link")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\": \"https://shop.example.com/p/9\", \"model\": \"gemini-from-backoffice\"}"))
            .andExpect(status().isOk());

        assertEquals("gemini-from-backoffice", stubGeminiClient.lastModel());
    }

    /** 모델을 안 보내는 구버전 호출자의 요청이 깨지지 않아야 한다 (계약의 additive 규칙). */
    @Test
    @DisplayName("model 을 안 보내면 지정 없음으로 흘러간다")
    void allowsMissingModel() throws Exception {
        stubGeminiClient.reset();
        stubGeminiClient.build = request -> new GeminiExtractionResult(
            true, "LLM 상품", 12_000, "KRW", "https://cdn.example.com/llm.png");
        stubPageFetcher.build = link -> PageContent.of(link, "<html><body>구조화 없음</body></html>");

        mockMvc().perform(post("/internal/extractions/link")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("https://shop.example.com/p/10")))
            .andExpect(status().isOk());

        assertNull(stubGeminiClient.lastModel());
    }
}
