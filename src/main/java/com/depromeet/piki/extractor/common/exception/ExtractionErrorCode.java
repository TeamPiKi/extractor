package com.depromeet.piki.extractor.common.exception;

/**
 * 실패 응답 body 의 code. docs/api-contract.md 의 code 표가 정본이다 — 호출자(PIKI-Server)는 이 값을
 * 전이 판정에 쓰지 않고(status 만 본다) 관측·디버깅에만 쓴다.
 */
public enum ExtractionErrorCode {
    NOT_PRODUCT_PAGE,
    UNTRUSTWORTHY_VALUE,
    FETCH_CLIENT_ERROR,
    BLOCKED_HOST,
    TOO_MANY_REDIRECTS,
    MALFORMED_REDIRECT,
    PERMANENT_UPSTREAM,
    LLM_INVALID_RESPONSE,
    INVALID_URL,
    UPSTREAM_ERROR,
    LLM_UPSTREAM,

    IMAGE_UNSUPPORTED,
    STORAGE_ERROR,

    /** 실제 브라우저로도 차단(verdict=BLOCK). 일시 챌린지(429 등)가 섞이므로 fail-safe 로 일시 취급한다. */
    HEADLESS_BLOCKED,
    HEADLESS_UPSTREAM,
}
