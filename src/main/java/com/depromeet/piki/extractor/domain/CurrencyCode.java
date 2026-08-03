package com.depromeet.piki.extractor.domain;

import java.util.Currency;
import java.util.Locale;

/**
 * LLM 추출값은 "krw" · " KRW " · "원" · "$" 처럼 제각각이고, 형식만 보면 "ZZZ" 같은 가짜도 통과한다 —
 * 그래서 {@link java.util.Currency} 로 실제 ISO 4217 코드 집합에 있는지까지 확인한다.
 * <p>{@code toUpperCase} 는 로케일 함정이 있어 {@code Locale.ROOT} 를 명시한다.
 */
public final class CurrencyCode {

    private CurrencyCode() {
    }

    public static String normalizeOrNull(String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        try {
            return Currency.getInstance(normalized).getCurrencyCode();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
