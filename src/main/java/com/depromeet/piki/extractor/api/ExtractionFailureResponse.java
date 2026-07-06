package com.depromeet.piki.extractor.api;

import com.depromeet.piki.extractor.common.exception.ExtractionErrorCode;

// 실패 응답 body. 계약(docs/api-contract.md): 호출자의 전이 판정은 HTTP status 만 쓰고, code 는 관측·디버깅용이다.
// message 등 내부 정보는 싣지 않는다 — 디버깅 컨텍스트는 이 서비스의 로그가 책임진다.
public record ExtractionFailureResponse(ExtractionErrorCode code) {
}
