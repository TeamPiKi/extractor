package com.depromeet.piki.extractor.extraction.gemini;

/** 구조화 파싱이 놓친 정가만 묻는 보강 호출의 응답. null 은 페이지에 정가 근거가 없다는 뜻이다. */
public record GeminiOriginalPriceResult(
    Integer originalPrice
) {}
