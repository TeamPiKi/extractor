package com.depromeet.piki.extractor.extraction.gemini;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * 무료 티어를 먼저 태우고 실패하면 유료로 넘어가는 규칙을 고정한다. 어느 티어가 불렸는지는 요청에 실린
 * API 키 헤더로만 구분되므로, 단언은 status 가 아니라 헤더를 본다.
 *
 * <p>여기서 지키는 선은 둘이다. (1) 400·404 는 넘어가지 않는다 — 400 은 우리 요청 결함이 유료의 성공으로
 * 덮이면 안 되고, 404 는 키가 아니라 모델의 문제라 모델 순회가 답한다. (2) 그 밖의 실패는 status 가 없는
 * 형태까지 전부 넘어간다 — 무료의 실패는 깔끔한 429 로만 오지 않기 때문이다.
 */
class GeminiHttpClientTierFallbackTest {

    private static final String DEFAULT_MODEL = GeminiProperties.DEFAULT_MODEL;
    private static final String REQUESTED_MODEL = "gemini-requested";
    private static final String FREE_KEY = "free-key";
    private static final String PAID_KEY = "paid-key";
    private static final String KEY_HEADER = "x-goog-api-key";

    private static final String EXTRACTION_JSON =
        "{\"isProductPage\":true,\"name\":\"나이키\",\"currentPrice\":99000,"
            + "\"currency\":\"KRW\",\"imageUrl\":\"https://cdn.example.com/i.png\"}";

    private static final String SUCCESS_BODY =
        "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":" + quoted(EXTRACTION_JSON) + "}]}}]}";

    /** 스키마는 유효하나 텍스트 파트가 없는 응답 — status 가 없는 실패(noTextPart)를 만든다. */
    private static final String NO_CANDIDATE_BODY = "{\"candidates\":[]}";

    private static String quoted(String raw) {
        return "\"" + raw.replace("\"", "\\\"") + "\"";
    }

    private MockRestServiceServer server;

    private GeminiHttpClient clientWithFreeKey(Consumer<MockRestServiceServer> expectations) {
        return clientWith(
            new GeminiProperties(PAID_KEY, FREE_KEY, DEFAULT_MODEL, new GeminiProperties.Retry()),
            expectations
        );
    }

    /**
     * 두 티어에 같은 클라이언트를 물려 하나의 mock 서버가 양쪽 호출을 순서대로 받게 한다. 티어를 가르는 것은
     * read 상한이 아니라 요청에 실리는 키라, 이렇게 해도 검증에 필요한 것은 전부 드러난다.
     */
    private GeminiHttpClient clientWith(
        GeminiProperties properties,
        Consumer<MockRestServiceServer> expectations
    ) {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://gemini.test");
        server = MockRestServiceServer.bindTo(builder).build();
        expectations.accept(server);
        RestClient restClient = builder.build();
        return new GeminiHttpClient(
            properties,
            new ObjectMapper(),
            new SimpleMeterRegistry(),
            restClient,
            restClient
        );
    }

    private static String uriOf(String model) {
        return "https://gemini.test/v1beta/models/" + model + ":generateContent";
    }

    @Test
    @DisplayName("무료가 성공하면 유료는 부르지 않는다")
    void staysOnFreeWhenItSucceeds() {
        GeminiHttpClient client = clientWithFreeKey(server ->
            server.expect(requestTo(uriOf(DEFAULT_MODEL)))
                .andExpect(header(KEY_HEADER, FREE_KEY))
                .andRespond(withSuccess(SUCCESS_BODY, MediaType.APPLICATION_JSON))
        );

        assertEquals("나이키", client.generateContent("{}", GeminiExtractionResult.class, null).name());
        // 유료 호출을 기대하지 않았으므로, 넘어갔다면 "예상 못한 요청"으로 깨진다.
        server.verify();
    }

    @Test
    @DisplayName("무료가 429 면 유료 키로 다시 부른다")
    void escalatesOnQuotaExhausted() {
        GeminiHttpClient client = clientWithFreeKey(server -> {
            server.expect(requestTo(uriOf(DEFAULT_MODEL)))
                .andExpect(header(KEY_HEADER, FREE_KEY))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
            server.expect(requestTo(uriOf(DEFAULT_MODEL)))
                .andExpect(header(KEY_HEADER, PAID_KEY))
                .andRespond(withSuccess(SUCCESS_BODY, MediaType.APPLICATION_JSON));
        });

        assertEquals("나이키", client.generateContent("{}", GeminiExtractionResult.class, null).name());
        server.verify();
    }

    @Test
    @DisplayName("status 가 없는 무료 실패도 유료로 넘어간다")
    void escalatesWhenFreeReturnsUnusableBody() {
        GeminiHttpClient client = clientWithFreeKey(server -> {
            server.expect(requestTo(uriOf(DEFAULT_MODEL)))
                .andExpect(header(KEY_HEADER, FREE_KEY))
                .andRespond(withSuccess(NO_CANDIDATE_BODY, MediaType.APPLICATION_JSON));
            server.expect(requestTo(uriOf(DEFAULT_MODEL)))
                .andExpect(header(KEY_HEADER, PAID_KEY))
                .andRespond(withSuccess(SUCCESS_BODY, MediaType.APPLICATION_JSON));
        });

        assertEquals("나이키", client.generateContent("{}", GeminiExtractionResult.class, null).name());
        server.verify();
    }

    @Test
    @DisplayName("무료의 400 은 유료로 넘어가지 않는다 - 요청 결함이 유료의 성공으로 덮이면 안 된다")
    void doesNotEscalateOnBadRequest() {
        GeminiHttpClient client = clientWithFreeKey(server ->
            server.expect(requestTo(uriOf(DEFAULT_MODEL)))
                .andExpect(header(KEY_HEADER, FREE_KEY))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST))
        );

        assertThrows(
            GeminiApiException.class,
            () -> client.generateContent("{}", GeminiExtractionResult.class, null)
        );
        server.verify();
    }

    @Test
    @DisplayName("무료의 404 는 티어가 아니라 모델 축에서 처리된다")
    void modelGoneStaysWithinFreeTier() {
        GeminiHttpClient client = clientWithFreeKey(server -> {
            server.expect(requestTo(uriOf(REQUESTED_MODEL)))
                .andExpect(header(KEY_HEADER, FREE_KEY))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
            server.expect(requestTo(uriOf(DEFAULT_MODEL)))
                .andExpect(header(KEY_HEADER, FREE_KEY))
                .andRespond(withSuccess(SUCCESS_BODY, MediaType.APPLICATION_JSON));
        });

        assertEquals(
            "나이키",
            client.generateContent("{}", GeminiExtractionResult.class, REQUESTED_MODEL).name()
        );
        server.verify();
    }

    @Test
    @DisplayName("무료 키가 없으면 유료로 한 번만 부른다")
    void paidOnlyWhenFreeKeyAbsent() {
        GeminiHttpClient client = clientWith(new GeminiProperties(PAID_KEY), server ->
            server.expect(requestTo(uriOf(DEFAULT_MODEL)))
                .andExpect(header(KEY_HEADER, PAID_KEY))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS))
        );

        assertThrows(
            GeminiApiException.class,
            () -> client.generateContent("{}", GeminiExtractionResult.class, null)
        );
        server.verify();
    }

    @Test
    @DisplayName("둘 다 실패하면 유료 쪽 실패가 전파된다")
    void propagatesPaidFailureWhenBothFail() {
        GeminiHttpClient client = clientWithFreeKey(server -> {
            server.expect(requestTo(uriOf(DEFAULT_MODEL)))
                .andExpect(header(KEY_HEADER, FREE_KEY))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
            server.expect(requestTo(uriOf(DEFAULT_MODEL)))
                .andExpect(header(KEY_HEADER, PAID_KEY))
                .andRespond(withServerError());
        });

        assertThrows(
            GeminiApiException.class,
            () -> client.generateContent("{}", GeminiExtractionResult.class, null)
        );
        server.verify();
    }

    @Test
    @DisplayName("프로브 경로는 무료 키가 있어도 유료 키로 부른다")
    void probeAlwaysUsesPaidKey() {
        GeminiHttpClient client = clientWithFreeKey(server ->
            server.expect(requestTo(uriOf(REQUESTED_MODEL)))
                .andExpect(header(KEY_HEADER, PAID_KEY))
                .andRespond(withSuccess(SUCCESS_BODY, MediaType.APPLICATION_JSON))
        );

        assertEquals(
            "나이키",
            client.generateContentExactly("{}", GeminiExtractionResult.class, REQUESTED_MODEL).name()
        );
        server.verify();
    }
}
