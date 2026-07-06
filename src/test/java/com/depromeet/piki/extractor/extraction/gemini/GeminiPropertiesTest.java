package com.depromeet.piki.extractor.extraction.gemini;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 기본 Gemini 모델이 "다른 모델로 바뀌는" 회귀를 빌드 단계에서 차단하는 가드.
 *
 * 기본값의 단일 진실 원천은 GeminiProperties.DEFAULT_MODEL 한 곳뿐이다 (application.yml 에는 리터럴을 두지 않는다).
 * 따라서 이 상수만 고정하면 운영·테스트 양쪽의 기본 모델이 함께 고정된다.
 */
class GeminiPropertiesTest {

    @Test
    @DisplayName("기본 모델은 gemini-3.1-flash-lite 로 고정된다")
    void defaultModelIsPinned() {
        assertEquals("gemini-3.1-flash-lite", GeminiProperties.DEFAULT_MODEL);
    }

    @Test
    @DisplayName("model 을 지정하지 않으면 DEFAULT_MODEL 이 적용된다")
    void defaultModelAppliesWhenOmitted() {
        GeminiProperties props = new GeminiProperties("dummy");

        assertEquals(GeminiProperties.DEFAULT_MODEL, props.model());
    }

    @Test
    @DisplayName("기본 모델은 preview 가 아닌 GA 모델이어야 한다")
    void defaultModelIsGa() {
        // preview 모델은 2 주 사전공지 후 deprecate 정책이라 운영 안정성상 기본값으로 두지 않는다.
        assertFalse(GeminiProperties.DEFAULT_MODEL.contains("preview"));
    }
}
