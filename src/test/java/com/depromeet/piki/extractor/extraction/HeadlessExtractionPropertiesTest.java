package com.depromeet.piki.extractor.extraction;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * enabled=true 일 때의 base-url fail-fast 분기를 망라한다.
 *
 * <p>오설정이 부팅을 통과하면 매 렌더 호출이 일시 실패로 위장돼 호출자의 recover 재시도 뒤에 숨는다 —
 * 그 시나리오를 부팅 실패로 앞당기는 계약이다.
 */
class HeadlessExtractionPropertiesTest {

    private HeadlessExtractionProperties propertiesWith(boolean enabled, String baseUrl) {
        return new HeadlessExtractionProperties(enabled, baseUrl, Duration.ofSeconds(2), Duration.ofSeconds(20), 3_000_000, true, "");
    }

    @ParameterizedTest(name = "enabled=true + base-url [{0}] 은 부팅에서 거부된다")
    @ValueSource(strings = {
        "",
        "headless.internal:8000", // 스킴 누락 — RFC 상 scheme=headless.internal 로 유효 파싱되는 함정
        "ftp://headless.internal:8000",
        "http://",
        "http://héad less", // URI 문법 위반(공백)
    })
    @DisplayName("enabled 인데 base-url 이 비었거나 http(s)://host 형태가 아니면 부팅에서 실패한다")
    void enabledRejectsInvalidBaseUrl(String baseUrl) {
        assertThrows(IllegalStateException.class, () -> propertiesWith(true, baseUrl));
    }

    @ParameterizedTest(name = "정상 base-url [{0}] 은 통과한다")
    @ValueSource(strings = {"http://10.0.1.41:8000", "https://headless.internal", "HTTP://headless.internal:8000"})
    @DisplayName("enabled 여도 http(s)://host 형태면 통과한다")
    void enabledAcceptsValidBaseUrl(String baseUrl) {
        assertDoesNotThrow(() -> propertiesWith(true, baseUrl));
    }

    @ParameterizedTest(name = "disabled 는 base-url [{0}] 을 검증하지 않는다")
    @ValueSource(strings = {"", "headless.internal:8000"})
    @DisplayName("disabled(기본) 는 base-url 이 어떤 값이어도 부팅을 막지 않는다 — env 없는 환경의 zero-diff")
    void disabledSkipsValidation(String baseUrl) {
        assertDoesNotThrow(() -> propertiesWith(false, baseUrl));
    }
}
