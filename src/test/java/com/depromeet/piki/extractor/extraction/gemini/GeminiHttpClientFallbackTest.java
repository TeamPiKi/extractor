package com.depromeet.piki.extractor.extraction.gemini;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
 * 지정 모델이 사라졌을 때 기본 모델로 이어가는 대체 규칙을 고정한다. 이 판정은 Gemini 가 준 status 에
 * 반응하므로 실제 클라이언트에 가짜 응답을 물려(MockRestServiceServer) 검증한다.
 *
 * <p>여기서 지키는 선은 하나다: 404 만 다음 후보로 넘어간다. 400 까지 넘기면 우리 요청 body 의 결함이 기본
 * 모델의 성공으로 덮여 조용히 묻히고, 5xx·timeout 까지 넘기면 모델을 바꾼다고 풀리지 않을 실패에 호출을
 * 두 배로 쓴다.
 */
class GeminiHttpClientFallbackTest {

    private static final String DEFAULT_MODEL = GeminiProperties.DEFAULT_MODEL;
    private static final String REQUESTED_MODEL = "gemini-requested";

    /** Gemini 응답 wire: 후보 1개의 텍스트 파트 안에 추출 결과 JSON 이 문자열로 들어 있다. */
    private static final String EXTRACTION_JSON =
        "{\"isProductPage\":true,\"name\":\"나이키\",\"currentPrice\":99000,"
            + "\"currency\":\"KRW\",\"imageUrl\":\"https://cdn.example.com/i.png\"}";

    private static final String SUCCESS_BODY =
        "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":" + quoted(EXTRACTION_JSON) + "}]}}]}";

    /** 안쪽 JSON 을 문자열 값으로 실으려면 따옴표를 이스케이프해야 한다. */
    private static String quoted(String raw) {
        return "\"" + raw.replace("\"", "\\\"") + "\"";
    }

    private MockRestServiceServer server;

    private GeminiHttpClient clientWith(Consumer<MockRestServiceServer> expectations) {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://gemini.test");
        server = MockRestServiceServer.bindTo(builder).build();
        expectations.accept(server);
        // 무료 키 없는 구성이라 티어 순회는 유료 하나뿐이고, 두 인자에 같은 클라이언트가 들어가도 무해하다.
        RestClient restClient = builder.build();
        return new GeminiHttpClient(
            new GeminiProperties("test-key"),
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
    @DisplayName("지정 모델이 404 면 기본 모델로 이어간다")
    void fallsBackWhenModelGone() {
        GeminiHttpClient client = clientWith(server -> {
            server.expect(requestTo(uriOf(REQUESTED_MODEL))).andRespond(withStatus(HttpStatus.NOT_FOUND));
            server.expect(requestTo(uriOf(DEFAULT_MODEL)))
                .andRespond(withSuccess(SUCCESS_BODY, MediaType.APPLICATION_JSON));
        });

        GeminiExtractionResult result =
            client.generateContent("{}", GeminiExtractionResult.class, REQUESTED_MODEL);

        assertEquals("나이키", result.name());
        server.verify();
    }

    @Test
    @DisplayName("400 은 대체하지 않는다 - 요청 body 결함이 기본 모델의 성공으로 덮이면 안 된다")
    void doesNotFallBackOnBadRequest() {
        GeminiHttpClient client = clientWith(server ->
            server.expect(requestTo(uriOf(REQUESTED_MODEL))).andRespond(withStatus(HttpStatus.BAD_REQUEST))
        );

        assertThrows(
            GeminiApiException.class,
            () -> client.generateContent("{}", GeminiExtractionResult.class, REQUESTED_MODEL)
        );
        // 기본 모델 호출을 기대하지 않았으므로, 대체가 일어났다면 여기서 "예상 못한 요청"으로 깨진다.
        server.verify();
    }

    @Test
    @DisplayName("5xx 는 대체하지 않는다 - 모델을 바꾼다고 풀리는 실패가 아니다")
    void doesNotFallBackOnServerError() {
        GeminiHttpClient client = clientWith(server ->
            server.expect(requestTo(uriOf(REQUESTED_MODEL))).andRespond(withServerError())
        );

        assertThrows(
            GeminiApiException.class,
            () -> client.generateContent("{}", GeminiExtractionResult.class, REQUESTED_MODEL)
        );
        server.verify();
    }

    @Test
    @DisplayName("모델 지정이 없으면 기본 모델만 친다")
    void usesDefaultWhenUnspecified() {
        GeminiHttpClient client = clientWith(server ->
            server.expect(requestTo(uriOf(DEFAULT_MODEL)))
                .andRespond(withSuccess(SUCCESS_BODY, MediaType.APPLICATION_JSON))
        );

        assertEquals("나이키", client.generateContent("{}", GeminiExtractionResult.class, null).name());
        server.verify();
    }

    /**
     * 지정 모델이 기본 모델과 같으면 후보가 하나여야 한다. 둘로 두면 404 를 두 번 맞고 대체 로그까지 남아,
     * 실제로는 없는 대체가 일어난 것처럼 보인다.
     */
    @Test
    @DisplayName("지정 모델이 기본 모델과 같으면 404 여도 다시 치지 않는다")
    void doesNotRetrySameModel() {
        GeminiHttpClient client = clientWith(server ->
            server.expect(requestTo(uriOf(DEFAULT_MODEL))).andRespond(withStatus(HttpStatus.NOT_FOUND))
        );

        assertThrows(
            GeminiApiException.class,
            () -> client.generateContent("{}", GeminiExtractionResult.class, DEFAULT_MODEL)
        );
        server.verify();
    }

    @Test
    @DisplayName("대체 없는 경로(프로브용)는 404 여도 기본 모델로 넘어가지 않는다")
    void exactlyDoesNotFallBack() {
        GeminiHttpClient client = clientWith(server ->
            server.expect(requestTo(uriOf(REQUESTED_MODEL))).andRespond(withStatus(HttpStatus.NOT_FOUND))
        );

        assertThrows(
            GeminiApiException.class,
            () -> client.generateContentExactly("{}", GeminiExtractionResult.class, REQUESTED_MODEL)
        );
        server.verify();
    }
}
