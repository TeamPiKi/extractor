package com.depromeet.piki.extractor.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.depromeet.piki.extractor.extraction.PageContent;
import com.depromeet.piki.extractor.extraction.headless.RenderedHop;
import com.depromeet.piki.extractor.extraction.http.PageFetchException;
import com.depromeet.piki.extractor.extraction.rule.RuleFixtures;
import com.depromeet.piki.extractor.support.IntegrationTestSupport;
import com.depromeet.piki.extractor.support.StubGeminiClient;
import com.depromeet.piki.extractor.support.StubHeadlessRenderer;
import com.depromeet.piki.extractor.support.StubPageFetcher;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** {@code POST /internal/rules/probe} 의 HTTP 계약. 호출자(core 백오피스)가 이 응답을 보고 규칙 저장을 정한다. */
class RuleProbeIntegrationTest extends IntegrationTestSupport {

    private static final String URL = "https://www.oliveyoung.co.kr/store/goods/1";

    private static final String RENDERED = """
        <html><head><meta property="og:image" content="https://image.oliveyoung.co.kr/a.jpg"></head><body>
          <h3 class="GoodsDetailInfo_title__x9Zk2">라운드랩 독도 토너</h3>
          <div class="GoodsDetailInfo_price__7hJ3a"><span>15,900</span>원</div>
        </body></html>""";

    private static final String JSON_LD = "<html><head><script type=\"application/ld+json\">"
        + "{\"@type\":\"Product\",\"name\":\"공통 규칙이 읽는 상품\",\"image\":\"https://cdn.example.com/p.png\","
        + "\"offers\":{\"price\":\"299000\",\"priceCurrency\":\"KRW\"}}"
        + "</script></head><body><p>299,000원</p></body></html>";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private StubPageFetcher stubPageFetcher;

    @Autowired
    private StubHeadlessRenderer stubHeadlessRenderer;

    @Autowired
    private StubGeminiClient stubGeminiClient;

    private ResultActions probe(String ruleJson) throws Exception {
        stubGeminiClient.reset();
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        return mockMvc.perform(post("/internal/rules/probe")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"url\": \"" + URL + "\", \"rule\": " + ruleJson + "}"));
    }

    private void rendersTo(String dom) {
        stubHeadlessRenderer.build = link -> List.of(new RenderedHop(link, 200, Map.of(), "", dom));
    }

    @Test
    @DisplayName("렌더 지정 규칙은 렌더한 문서에서 읽은 값과 할인 없음 선언의 적용 여부를 답한다")
    void probesRenderedDocument() throws Exception {
        stubPageFetcher.build = link -> {
            throw new IllegalStateException("렌더 지정 규칙은 정적 fetch 를 하지 않는다");
        };
        rendersTo(RENDERED);

        probe(RuleFixtures.OLIVE_YOUNG)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.matched").value(true))
            .andExpect(jsonPath("$.byRule.name").value("라운드랩 독도 토너"))
            .andExpect(jsonPath("$.byRule.currentPrice").value(15_900))
            .andExpect(jsonPath("$.byRule.originalPrice").doesNotExist())
            .andExpect(jsonPath("$.unreadFields[0]").value("originalPrice"))
            .andExpect(jsonPath("$.noDiscountApplied").value(true))
            .andExpect(jsonPath("$.discarded").doesNotExist())
            .andExpect(jsonPath("$.finalUrl").value(URL));
    }

    @Test
    @DisplayName("규칙이 출처를 적은 필드를 못 읽으면 버려짐으로 답하고 공통 규칙이 읽은 값을 함께 준다")
    void reportsDiscardedRuleWithCommonReading() throws Exception {
        stubPageFetcher.build = link -> PageContent.of(link, JSON_LD);

        probe("""
            {"id": 5, "host": "oliveyoung.co.kr", "currentPrice": {"css": {"selector": ".no-such"}}}""")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.matched").value(true))
            .andExpect(jsonPath("$.discarded").value(true))
            .andExpect(jsonPath("$.unreadFields[0]").value("currentPrice"))
            .andExpect(jsonPath("$.byCommon.name").value("공통 규칙이 읽는 상품"))
            .andExpect(jsonPath("$.byCommon.currentPrice").value(299_000));
    }

    @Test
    @DisplayName("규칙의 대상이 아닌 페이지면 matched 없이 공통 규칙이 읽은 값만 준다")
    void reportsUnmatchedRule() throws Exception {
        stubPageFetcher.build = link -> PageContent.of(link, JSON_LD);

        probe("{\"id\": 5, \"host\": \"29cm.co.kr\", \"absentOriginalPrice\": \"NO_DISCOUNT\"}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.matched").doesNotExist())
            .andExpect(jsonPath("$.noDiscountApplied").doesNotExist())
            .andExpect(jsonPath("$.byCommon.currentPrice").value(299_000));
    }

    @Test
    @DisplayName("해석하지 못하는 규칙은 페이지를 받지 않고 200 의 ruleError 로 답한다")
    void reportsRuleErrorWithoutFetching() throws Exception {
        stubPageFetcher.build = link -> {
            throw new IllegalStateException("해석 못 한 규칙으로는 페이지를 받지 않는다");
        };

        probe("{\"id\": 5, \"host\": \"oliveyoung.co.kr\", \"name\": {\"css\": {\"selector\": \"div[\"}}}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ruleError").isNotEmpty())
            .andExpect(jsonPath("$.matched").doesNotExist());
    }

    @Test
    @DisplayName("정적 fetch 가 승격 대상 차단이면 렌더한 문서로 시험한다")
    void escalatesBlockedFetchToRender() throws Exception {
        stubPageFetcher.build = link -> {
            throw PageFetchException.clientError(new RuntimeException("403"));
        };
        rendersTo(JSON_LD);

        probe("{\"id\": 5, \"host\": \"oliveyoung.co.kr\", \"absentOriginalPrice\": \"NO_DISCOUNT\"}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.matched").value(true))
            .andExpect(jsonPath("$.noDiscountApplied").value(true));
    }

    @Test
    @DisplayName("url 이나 rule 이 빠지면 400 이다")
    void rejectsMissingField() throws Exception {
        MockMvcBuilders.webAppContextSetup(context).build()
            .perform(post("/internal/rules/probe")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\": \"" + URL + "\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("페이지를 받지 못하면 추출 경로와 같은 실패 응답을 준다")
    void propagatesFetchFailure() throws Exception {
        stubPageFetcher.build = link -> {
            throw PageFetchException.blockedHost();
        };

        probe("{\"id\": 5, \"host\": \"oliveyoung.co.kr\"}")
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("BLOCKED_HOST"));
    }
}
