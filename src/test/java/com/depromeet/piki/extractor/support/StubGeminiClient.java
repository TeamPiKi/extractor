package com.depromeet.piki.extractor.support;

import com.depromeet.piki.extractor.extraction.gemini.GeminiClient;
import java.util.function.Function;

// 외부 LLM 호출 경계를 통합 테스트에서 격리하는 stub. build 람다로 응답을 교체하고,
// invocations 카운터로 "구조화 우선 파싱이 성공하면 LLM 을 호출하지 않는다"를 단언한다.
// default build 는 throw — 명시 세팅을 빠뜨리면 즉시 깨진다. 매 테스트가 본문에서 reset()+build 를 세팅한다.
public class StubGeminiClient implements GeminiClient {

    private int invocations = 0;

    // 마지막 호출의 request 를 캡처한다. fallback 시 LLM 으로 보낸 입력(sanitize 결과)을 검증하는 데 쓴다.
    private Object lastRequest;

    public Function<Object, Object> build = request -> {
        throw new IllegalStateException("stub.build 를 테스트 본문에서 명시 세팅해야 한다. CLAUDE.md '테스트' 절 참고.");
    };

    @Override
    @SuppressWarnings("unchecked")
    public <Req, Res> Res generateContent(Req request, Class<Res> resultType) {
        invocations++;
        lastRequest = request;
        return (Res) build.apply(request);
    }

    public int invocations() {
        return invocations;
    }

    public Object lastRequest() {
        return lastRequest;
    }

    public void reset() {
        invocations = 0;
        lastRequest = null;
    }
}
