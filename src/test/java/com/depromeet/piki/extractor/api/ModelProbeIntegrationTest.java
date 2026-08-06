package com.depromeet.piki.extractor.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.depromeet.piki.extractor.common.exception.ExtractionErrorCode;
import com.depromeet.piki.extractor.extraction.gemini.GeminiApiException;
import com.depromeet.piki.extractor.support.IntegrationTestSupport;
import com.depromeet.piki.extractor.support.StubGeminiClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.context.WebApplicationContext;

/**
 * {@code POST /internal/models/probe} 의 HTTP 계약. 호출자(core 백오피스)는 이 status 로 "저장을 허용할지"를
 * 가르므로, status 와 code 가 곧 저장 게이트의 계약이다.
 *
 * <p>422 는 저장 거부, 502 는 "지금은 확인할 수 없음"(재시도 안내), 400 은 호출자 구현 버그다.
 */
class ModelProbeIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private StubGeminiClient stubGeminiClient;

    private MockMvc mockMvc() {
        return MockMvcBuilders.webAppContextSetup(context).build();
    }

    private String body(String model, String target) {
        return "{\"model\": \"" + model + "\", \"target\": \"" + target + "\"}";
    }

    private void stubFailsWith(RuntimeException failure) {
        stubGeminiClient.reset();
        stubGeminiClient.build = request -> {
            throw failure;
        };
    }

    private static GeminiApiException responseError(int status) {
        return GeminiApiException.fromResponseError(
            new RestClientResponseException("probe", status, String.valueOf(status), null, null, null)
        );
    }

    @Test
    @DisplayName("호출이 통과하면 200 을 준다 - body 는 없다")
    void ok() throws Exception {
        stubGeminiClient.reset();
        stubGeminiClient.build = request -> null;

        mockMvc().perform(post("/internal/models/probe")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("gemini-ok", "LINK")))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("없는 모델은 422 MODEL_NOT_FOUND 로 거절한다")
    void notFound() throws Exception {
        stubFailsWith(responseError(404));

        mockMvc().perform(post("/internal/models/probe")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("gemini-nonexistent", "LINK")))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value(ExtractionErrorCode.MODEL_NOT_FOUND.name()));
    }

    @Test
    @DisplayName("요청을 처리하지 못하는 모델은 422 MODEL_INCOMPATIBLE 로 거절한다")
    void incompatible() throws Exception {
        stubFailsWith(responseError(400));

        mockMvc().perform(post("/internal/models/probe")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("gemini-wrong-shape", "IMAGE")))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value(ExtractionErrorCode.MODEL_INCOMPATIBLE.name()));
    }

    /**
     * 이 갈래가 422 로 새면 외부가 잠깐 흔들린 사이에 호출자가 멀쩡한 모델을 "쓸 수 없다"고 판정해 저장을
     * 막는다. 일시 실패는 반드시 502 로 나가야 재시도 안내가 된다.
     */
    @Test
    @DisplayName("외부 사정으로 확인이 안 되면 502 로 준다")
    void transient_() throws Exception {
        stubFailsWith(responseError(503));

        mockMvc().perform(post("/internal/models/probe")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("gemini-ok", "LINK")))
            .andExpect(status().isBadGateway());
    }

    @Test
    @DisplayName("model 이 비면 400 - 재시도해도 같은 결과라 일시 실패로 위장하지 않는다")
    void missingModel() throws Exception {
        mockMvc().perform(post("/internal/models/probe")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"target\": \"LINK\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("모르는 target 은 400 - 경로를 특정하지 못하면 프로브가 성립하지 않는다")
    void unknownTarget() throws Exception {
        mockMvc().perform(post("/internal/models/probe")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("gemini-ok", "SOMETHING_ELSE")))
            .andExpect(status().isBadRequest());
    }
}
