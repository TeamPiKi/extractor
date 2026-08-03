package com.depromeet.piki.extractor.api;

/**
 * POST /internal/extractions/image 요청 (docs/api-contract.md §2).
 *
 * <p>bucket 을 고정 config 가 아니라 요청이 준다 — extractor 한 대가 dev/staging/prod 세 환경 트래픽을
 * 받고 환경마다 이미지 버킷이 달라서, 요청별로 받아 버킷 무관하게 동작해야 한다. key 는 등록 시 호출자가
 * 적재한 raw object key 다.
 */
public record ImageExtractionRequest(String bucket, String key) {
}
