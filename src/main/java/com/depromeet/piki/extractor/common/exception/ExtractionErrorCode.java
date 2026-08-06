package com.depromeet.piki.extractor.common.exception;

/**
 * 실패 응답 body 의 code. docs/api-contract.md 의 code 표와 1:1 이어야 한다 — 추가는 자유(additive),
 * 제거·의미 변경 금지. 호출자(core)는 이 값을 전이 판정에 쓰지 않고(status 만 본다) 관측·디버깅에만 쓴다.
 * <p>일시/확정 분류의 정본은 각 예외 팩토리의 permanent 플래그다 — 여기 복제하지 않는다.
 */
public enum ExtractionErrorCode {
    NOT_PRODUCT_PAGE,
    UNTRUSTWORTHY_VALUE,
    FETCH_CLIENT_ERROR,
    BLOCKED_HOST,
    TOO_MANY_REDIRECTS,
    MALFORMED_REDIRECT,
    PERMANENT_UPSTREAM,
    /** fetch 는 2xx 였지만 본문이 데이터 없는 CSR 셸 — 파싱 no-data 를 escalatable 로 재분류한 것(EmptyShellDetector). */
    EMPTY_SHELL,
    LLM_INVALID_RESPONSE,
    INVALID_URL,
    UPSTREAM_ERROR,
    LLM_UPSTREAM,

    IMAGE_UNSUPPORTED,
    STORAGE_ERROR,

    /** 실제 브라우저로도 차단(verdict=BLOCK). 일시 챌린지(429 등)가 섞이므로 fail-safe 로 일시 취급한다. */
    HEADLESS_BLOCKED,
    HEADLESS_UPSTREAM,

    /** 모델 프로브 전용: 그런 모델이 없다(404). 오타이거나 폐기돼 사라진 모델이다. */
    MODEL_NOT_FOUND,

    /** 모델 프로브 전용: 모델은 있으나 그 경로의 요청을 처리하지 못한다(요청 스키마 비호환·결제 티어 제한 등). */
    MODEL_INCOMPATIBLE,
}
