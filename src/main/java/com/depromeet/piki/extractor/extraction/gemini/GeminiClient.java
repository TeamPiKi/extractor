package com.depromeet.piki.extractor.extraction.gemini;

/**
 * Gemini generateContent 호출 경계. 추출기들이 구현(GeminiHttpClient)이 아니라 이 인터페이스에만 의존하게 해,
 * 통합 테스트가 외부 LLM 호출을 stub 으로 격리할 수 있다(테스트 규약 "외부 호출 경계는 인터페이스 + stub 구현").
 */
public interface GeminiClient {

    /**
     * @param requestedModel 호출자(core 백오피스)가 지정한 모델. null 이면 기본 모델을 쓴다 — 지정이 없는 상태를
     *     그대로 흘려보내는 것이 계약이라(docs/api-contract.md) 여기서 기본값을 미리 채우지 않는다.
     *     지정한 모델이 사라졌으면(404) 구현이 기본 모델로 대체한다.
     */
    <Req, Res> Res generateContent(Req request, Class<Res> resultType, String requestedModel);

    /**
     * 지정 모델만 시도한다 — 대체가 없다.
     *
     * <p>모델 프로브 전용이다. 프로브는 "이 모델이 되는가"를 묻는 것이라, 대체가 일어나면 없는 모델을 넣어도
     * 기본 모델이 대신 성공해 통과해 버린다. 저장 게이트가 그렇게 무력화되면 프로브를 둔 의미가 사라진다.
     */
    <Req, Res> Res generateContentExactly(Req request, Class<Res> resultType, String model);
}
