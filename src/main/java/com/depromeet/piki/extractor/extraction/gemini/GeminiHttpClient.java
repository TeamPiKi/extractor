package com.depromeet.piki.extractor.extraction.gemini;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.ObjectMapper;

/**
 * Gemini generateContent 호출의 공통 뼈대. 추출기가 자기 Request/Result 타입만 알면 되도록
 * 호출·재시도·에러 분류·응답 파싱을 이 한 곳으로 흡수한다.
 */
@Component
public class GeminiHttpClient implements GeminiClient {

    private static final String BASE_URL = "https://generativelanguage.googleapis.com";
    private static final int CONNECT_TIMEOUT_MS = 5_000;

    /**
     * LLM 응답이 길어질 수 있어 넉넉히 두되, 호출자 read 예산 안에 들도록 상한을 둔다
     * (층별 예산의 정본은 docs/api-contract.md §3).
     */
    private static final int READ_TIMEOUT_MS = 30_000;

    /**
     * API 키는 access log 에 남지 않도록 쿼리 대신 헤더로 전달한다.
     *
     * @see <a href="https://ai.google.dev/gemini-api/docs/api-key#provide-api-key-explicitly">Gemini API key</a>
     */
    private static final String GEMINI_API_KEY_HEADER = "x-goog-api-key";

    private final GeminiProperties geminiProperties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final GeminiRetry geminiRetry;

    /** ObservationRegistry 를 물려 Gemini 호출이 요청 trace 안의 HTTP client span 으로 잡히게 한다. */
    public GeminiHttpClient(
        GeminiProperties geminiProperties,
        ObjectMapper objectMapper,
        ObservationRegistry observationRegistry
    ) {
        this.geminiProperties = geminiProperties;
        this.objectMapper = objectMapper;

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        requestFactory.setReadTimeout(READ_TIMEOUT_MS);
        this.restClient = RestClient.builder()
            .baseUrl(BASE_URL)
            .requestFactory(requestFactory)
            .observationRegistry(observationRegistry)
            .build();

        this.geminiRetry = new GeminiRetry(geminiProperties.retry());
    }

    @Override
    public <Req, Res> Res generateContent(Req request, Class<Res> resultType) {
        return geminiRetry.execute(() -> {
            GeminiGenerateContentResponse response;
            try {
                response = restClient.post()
                    .uri(uriBuilder -> uriBuilder
                        .path("/v1beta/models/{model}:generateContent")
                        .build(geminiProperties.model()))
                    .header(GEMINI_API_KEY_HEADER, geminiProperties.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(GeminiGenerateContentResponse.class);
            } catch (RestClientResponseException e) {
                throw GeminiApiException.fromResponseError(e);
            } catch (ResourceAccessException e) {
                throw GeminiApiException.upstreamError(e);
            } catch (RestClientException e) {
                // 응답 본문 추출 중 read-timeout 등은 RestClientResponseException 도 ResourceAccessException 도 아닌
                // raw RestClientException 으로 온다. 위 두 catch 를 빠져나가 500 으로 새던 것을 막고,
                // transport 장애로 보고 재시도 대상(502)으로 분류한다.
                throw GeminiApiException.upstreamError(e);
            }
            if (response == null) {
                throw GeminiApiException.emptyResponse();
            }

            String text = response.extractText();
            try {
                return objectMapper.readValue(text, resultType);
            } catch (Exception e) {
                throw GeminiApiException.parseError(e);
            }
        });
    }
}
