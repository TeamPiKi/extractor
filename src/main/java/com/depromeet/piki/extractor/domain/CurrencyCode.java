package com.depromeet.piki.extractor.domain;

import java.util.Currency;
import java.util.Locale;

// 통화 코드 정규화. LLM 추출값은 "krw" · " KRW " · "원" · "$" 처럼 형식이 제각각이라,
// trim·대문자화 후 java.util.Currency 로 실제 ISO 4217 코드 집합에 있는지까지 검증한다.
// 형식만 보면 "ZZZ" 같은 가짜도 통과하므로 실존 코드 검증으로 막는다. 안 맞으면 null.
// (toUpperCase 는 로케일 함정이 있어 Locale.ROOT 를 명시한다.)
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
