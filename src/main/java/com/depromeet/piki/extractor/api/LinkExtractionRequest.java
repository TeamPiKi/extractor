package com.depromeet.piki.extractor.api;

/**
 * POST /internal/extractions/link 요청 (docs/api-contract.md §2).
 *
 * <p>url 형식 검증을 Bean Validation 이 아니라 {@code ProductLink.parse} 에 맡긴다 — blank·형식·스킴
 * 위반이 전부 계약 코드 INVALID_URL 하나로 떨어져야 하기 때문이다.
 *
 * <p>headlessFirst 를 primitive boolean 이 아니라 Boolean 으로 받는 이유: Jackson 3 는
 * FAIL_ON_NULL_FOR_PRIMITIVES 가 기본 on 이라, 이 선택 필드를 안 보내는 구버전 호출자의 요청이 400 으로
 * 깨진다(통합 테스트로 실측). 의미는 {@code ProductLinkExtractor.extract} 의 headlessFirst 와 같다.
 */
public record LinkExtractionRequest(String url, Boolean headlessFirst) {

    public LinkExtractionRequest {
        headlessFirst = Boolean.TRUE.equals(headlessFirst);
    }
}
