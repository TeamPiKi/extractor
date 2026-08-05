package com.depromeet.piki.extractor.support;

import com.depromeet.piki.extractor.extraction.gemini.GeminiClient;
import java.util.function.Function;

/**
 * 외부 LLM 호출 경계를 통합 테스트에서 격리하는 stub. invocations 카운터로 "구조화 우선 파싱이 성공하면 LLM 을
 * 호출하지 않는다"를 단언한다.
 *
 * <p>default build 는 throw — 명시 세팅을 빠뜨리면 즉시 깨진다. 매 테스트가 본문에서 {@code reset()}+build 를
 * 세팅한다.
 */
public class StubGeminiClient implements GeminiClient {

    private int invocations = 0;

    /** fallback 시 LLM 으로 보낸 입력(sanitize 결과)을 검증하는 데 쓴다. */
    private Object lastRequest;

    /** 요청에 실려 온 모델 힌트가 여기까지 흘렀는지 단언하는 데 쓴다. 지정이 없으면 null 이다. */
    private String lastModel;

    public Function<Object, Object> build = request -> {
        throw new IllegalStateException("stub.build 를 테스트 본문에서 명시 세팅해야 한다. CLAUDE.md '테스트' 절 참고.");
    };

    @Override
    public <Req, Res> Res generateContent(Req request, Class<Res> resultType, String requestedModel) {
        return call(request, requestedModel);
    }

    /**
     * 대체 없는 경로도 같은 기록을 남긴다. stub 은 대체를 흉내내지 않는다 — 후보 순회는 실제 HTTP status 에
     * 반응하는 로직이라 {@code GeminiHttpClientFallbackTest} 가 실제 클라이언트로 검증한다.
     */
    @Override
    public <Req, Res> Res generateContentExactly(Req request, Class<Res> resultType, String model) {
        return call(request, model);
    }

    @SuppressWarnings("unchecked")
    private <Res> Res call(Object request, String model) {
        invocations++;
        lastRequest = request;
        lastModel = model;
        return (Res) build.apply(request);
    }

    public int invocations() {
        return invocations;
    }

    public Object lastRequest() {
        return lastRequest;
    }

    public String lastModel() {
        return lastModel;
    }

    public void reset() {
        invocations = 0;
        lastRequest = null;
        lastModel = null;
    }
}
