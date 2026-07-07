package com.depromeet.piki.extractor.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class CurrencyCodeTest {

    @ParameterizedTest
    @CsvSource({
        "KRW, KRW",
        "krw, KRW",
        "' KRW ', KRW",
        "usd, USD",
        "Jpy, JPY",
    })
    @DisplayName("대소문자·공백을 정규화해 ISO 4217 3자리 대문자로 만든다")
    void normalizesToIso4217(String raw, String expected) {
        assertEquals(expected, CurrencyCode.normalizeOrNull(raw));
    }

    @ParameterizedTest
    @ValueSource(strings = {"원", "$", "￦", "US", "DOLLAR", "12A", "K-W"})
    @DisplayName("ISO 4217 3자리 형식이 아니면 null 로 떨어뜨린다")
    void nonIso4217FormatIsNull(String raw) {
        assertNull(CurrencyCode.normalizeOrNull(raw));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ZZZ", "ABC", "QQQ"})
    @DisplayName("형식은 맞지만 실제 ISO 4217 코드가 아니면 null 로 떨어뜨린다")
    void formatValidButNotRealCodeIsNull(String raw) {
        assertNull(CurrencyCode.normalizeOrNull(raw));
    }

    @Test
    @DisplayName("null·빈·공백은 null 이다")
    void nullBlankIsNull() {
        assertNull(CurrencyCode.normalizeOrNull(null));
        assertNull(CurrencyCode.normalizeOrNull(""));
        assertNull(CurrencyCode.normalizeOrNull("   "));
    }
}
