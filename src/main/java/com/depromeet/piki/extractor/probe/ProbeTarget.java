package com.depromeet.piki.extractor.probe;

/**
 * 프로브 대상 경로. 링크와 이미지는 같은 Gemini 를 치지만 요청 wire 가 다르다 — 링크는
 * {@code responseJsonSchema} 에 소문자 type, 이미지는 {@code responseSchema} 에 대문자 enum type 과
 * thinkingConfig 를 싣는다. 한쪽에서 통과한 모델이 다른 쪽에서 400 일 수 있어, 프로브도 경로별로 갈린다.
 */
public enum ProbeTarget {
    LINK,
    IMAGE,
}
