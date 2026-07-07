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

    // 이미지(OCR) 경로 (docs/api-contract.md §2 image code 추가분)
    IMAGE_UNSUPPORTED, // 확정 실패 — 이미지 형식 불가(빈 이미지·미지원 MIME)
    STORAGE_ERROR,     // 일시 실패 — S3 read/write 오류

    // 헤드리스 렌더 경로
    HEADLESS_BLOCKED,  // 일시 실패 — 실제 브라우저로도 차단(verdict=BLOCK). 일시 챌린지(429 등)가 섞여 fail-safe 로 일시
    HEADLESS_UPSTREAM, // 일시 실패 — 렌더 서비스 연결 실패·타임아웃·빈 렌더·브라우저 오류
}
