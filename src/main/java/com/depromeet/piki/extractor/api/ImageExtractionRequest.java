package com.depromeet.piki.extractor.api;

/**
 * POST /internal/extractions/image 요청 (docs/api-contract.md §2).
 *
 * <p>bucket 을 고정 config 가 아니라 요청이 준다 — extractor 한 대가 여러 환경의 트래픽을 받고 환경마다
 * 이미지 버킷이 달라서, 요청별로 받아 버킷 무관하게 동작해야 한다. key 는 등록 시 호출자가 적재한 raw object key 다.
 *
 * <p>model 도 같은 이유로 요청이 준다 — 환경마다 지정 모델이 다를 수 있는데 이 서비스는 그 구분을 모른다.
 * null 이면 기본 모델을 쓴다(빈 문자열·공백 처리는 GeminiHttpClient 의 후보 계산이 흡수한다).
 */
public record ImageExtractionRequest(String bucket, String key, String model) {
}
