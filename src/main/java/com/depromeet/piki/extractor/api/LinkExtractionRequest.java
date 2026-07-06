package com.depromeet.piki.extractor.api;

// POST /internal/extractions/link 요청. url 의 형식 검증은 Bean Validation 이 아니라 ProductLink.parse 가 맡는다 —
// blank·형식·스킴 위반이 전부 계약 코드 INVALID_URL(422) 하나로 떨어져야 하기 때문(docs/api-contract.md).
public record LinkExtractionRequest(String url) {
}
