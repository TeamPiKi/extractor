package com.depromeet.piki.extractor.common.exception;

// 422 응답 body 의 code. docs/api-contract.md 의 code 표와 1:1 이어야 한다 — 추가는 자유(additive), 제거·의미 변경 금지.
// 호출자(PIKI-Server)는 이 값을 전이 판정에 쓰지 않고(status 만 사용) 관측·디버깅에만 쓴다.
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
}
