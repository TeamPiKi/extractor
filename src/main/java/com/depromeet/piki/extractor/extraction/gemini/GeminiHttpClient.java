package com.depromeet.piki.extractor.extraction.gemini;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
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
@Slf4j
@Component
public class GeminiHttpClient implements GeminiClient {

    private static final String BASE_URL = "https://generativelanguage.googleapis.com";
    private static final int CONNECT_TIMEOUT_MS = 5_000;

    /**
     * 지정 모델이 사라져 기본 모델로 대체된 횟수. 모델명을 태그로 달지 않는다 — 호출자가 백오피스에서 임의
     * 문자열을 넣을 수 있어 시계열 카디널리티에 상한이 없기 때문이다. 어느 모델이 죽었는지는 함께 남기는
     * warn 로그가 답한다(메트릭은 추세, 로그는 원장).
     */
    private static final String MODEL_FALLBACK_METRIC = "gemini.model.fallback";

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
    private final MeterRegistry meterRegistry;

    /**
     * ObservationRegistry 를 물려 Gemini 호출이 요청 trace 안의 HTTP client span 으로 잡히게 한다.
     *
     * <p>{@code @Autowired} 를 명시하는 이유: 아래 테스트용 생성자가 생기면서 후보가 둘이 됐고, 그러면 Spring 은
     * 자동 선택을 포기하고 기본 생성자를 찾다 실패한다. 운영 조립 지점이 여기임을 못박는다.
     */
    @Autowired
    public GeminiHttpClient(
        GeminiProperties geminiProperties,
        ObjectMapper objectMapper,
        ObservationRegistry observationRegistry,
        MeterRegistry meterRegistry
    ) {
        this(geminiProperties, objectMapper, meterRegistry, defaultRestClient(observationRegistry));
    }

    /**
     * RestClient 를 밖에서 받는 생성자. 후보 모델 순회는 Gemini 가 준 status 에 반응하는 로직이라, 가짜 응답을
     * 끼우지 않고는 검증할 수 없다 — 단위 테스트가 MockRestServiceServer 를 물릴 수 있게 열어 둔다.
     * 운영 조립은 위 생성자가 하므로 이 생성자는 패키지 밖으로 나가지 않는다.
     */
    GeminiHttpClient(
        GeminiProperties geminiProperties,
        ObjectMapper objectMapper,
        MeterRegistry meterRegistry,
        RestClient restClient
    ) {
        this.geminiProperties = geminiProperties;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.restClient = restClient;
        this.geminiRetry = new GeminiRetry(geminiProperties.retry());
    }

    private static RestClient defaultRestClient(ObservationRegistry observationRegistry) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        requestFactory.setReadTimeout(READ_TIMEOUT_MS);
        return RestClient.builder()
            .baseUrl(BASE_URL)
            .requestFactory(requestFactory)
            .observationRegistry(observationRegistry)
            .build();
    }

    /**
     * 후보 모델을 순서대로 시도한다. 목록은 [요청 모델, 기본 모델] 둘뿐이지만 순회로 짜 둔 것은, 후보를 N 개로
     * 늘릴 때 데이터만 바뀌고 이 로직이 그대로이길 바라서다.
     *
     * <p>다음 후보로 넘어가는 유일한 신호는 404 다. 등록 당시엔 유효했던 모델이 폐기돼 사라진 경우로,
     * 이때 파싱 전체가 죽는 대신 기본 모델로 이어가는 편이 낫다(가용성 우선). 400·5xx·timeout 은 넘어가지
     * 않는다 — 400 은 우리 요청 body 쪽 문제일 수 있어 대체 모델로 덮으면 버그가 조용히 묻히고,
     * 나머지는 모델을 바꾼다고 풀리는 실패가 아니다.
     */
    @Override
    public <Req, Res> Res generateContent(Req request, Class<Res> resultType, String requestedModel) {
        List<String> candidates = candidates(requestedModel);
        for (int i = 0; i < candidates.size(); i++) {
            String model = candidates.get(i);
            boolean hasNext = i < candidates.size() - 1;
            try {
                return callWithRetry(request, resultType, model);
            } catch (GeminiApiException e) {
                if (!hasNext || !modelGone(e)) {
                    throw e;
                }
                meterRegistry.counter(MODEL_FALLBACK_METRIC).increment();
                log.warn("Gemini 모델 {} 이 응답하지 않아 다음 후보로 대체합니다.", model);
            }
        }
        // 위 루프는 마지막 후보에서 반드시 반환하거나 던진다 — 여기 닿으면 순회 로직 자체의 버그다.
        throw new IllegalStateException("모델 후보를 모두 소진했는데 결과도 예외도 없다.");
    }

    /**
     * 요청 모델이 비었거나 기본 모델과 같으면 후보는 하나다 — 같은 모델을 두 번 두면 404 를 두 번 맞고
     * 대체 로그까지 남아, 실제로는 없는 fallback 이 일어난 것처럼 보인다.
     *
     * <p>빈 문자열과 앞뒤 공백을 여기서 흡수한다. 요청 DTO 마다 같은 정규화를 두면 링크·이미지·프로브 세 군데로
     * 흩어져 한쪽만 바뀔 수 있어, 모델 문자열을 실제로 쓰는 이 지점 하나로 모은다.
     */
    private List<String> candidates(String requestedModel) {
        String defaultModel = geminiProperties.model();
        if (requestedModel == null || requestedModel.isBlank()) {
            return List.of(defaultModel);
        }
        String trimmed = requestedModel.trim();
        if (trimmed.equals(defaultModel)) {
            return List.of(defaultModel);
        }
        return List.of(trimmed, defaultModel);
    }

    /** 지정 모델이 사라졌다는 신호. status 가 없는 실패(transport 장애 등)는 판단 근거가 아니다. */
    private boolean modelGone(GeminiApiException e) {
        Integer status = e.httpStatus();
        return status != null && status == HttpStatus.NOT_FOUND.value();
    }

    @Override
    public <Req, Res> Res generateContentExactly(Req request, Class<Res> resultType, String model) {
        return callWithRetry(request, resultType, model);
    }

    private <Req, Res> Res callWithRetry(Req request, Class<Res> resultType, String model) {
        return geminiRetry.execute(() -> {
            GeminiGenerateContentResponse response;
            try {
                response = restClient.post()
                    .uri(uriBuilder -> uriBuilder
                        .path("/v1beta/models/{model}:generateContent")
                        .build(model))
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
