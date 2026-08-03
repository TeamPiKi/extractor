package com.depromeet.piki.extractor.extraction.headless;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * POST /render 응답 wire 모델. 우리가 쓰는 필드만 선언하는 tolerant reader 라, 구버전 renderer 가 주던
 * title/price/source 나 새로 붙는 필드나 똑같이 흡수된다.
 *
 * <p>verdict 는 렌더 서비스의 판정 문자열이고 "가격 찾음" 이 아니라 "html 확보" 여부다(renderer 는 파싱하지
 * 않는다). 계약으로의 번역 규칙은 HttpHeadlessRenderer 가 정본이다.
 *
 * <p>전 필드 nullable(박싱 타입) — 렌더 서비스의 예외 격리 경로는 platform·verdict·error 만 싣는다. primitive 를
 * 쓰면 Jackson 3(FAIL_ON_NULL_FOR_PRIMITIVES 기본 on)가 필드 부재를 역직렬화 실패로 만들어, verdict 번역에
 * 닿기도 전에 일시 실패로 오분류된다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record HeadlessRenderResponse(
    String verdict,
    Boolean proxied,
    Integer status,
    @JsonProperty("final_url") String finalUrl,
    String html,
    String error
) {
}
