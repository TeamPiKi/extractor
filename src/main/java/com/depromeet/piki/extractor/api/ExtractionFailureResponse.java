package com.depromeet.piki.extractor.api;

import com.depromeet.piki.extractor.common.exception.ExtractionErrorCode;

/**
 * 실패 응답 body (docs/api-contract.md). message 같은 내부 정보는 싣지 않는다 — 디버깅 컨텍스트는
 * 이 서비스의 로그가 책임진다.
 */
public record ExtractionFailureResponse(ExtractionErrorCode code) {
}
