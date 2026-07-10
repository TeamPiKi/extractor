package com.depromeet.piki.extractor.extraction.headless;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

// POST /render 응답 wire 모델. 우리가 쓰는 필드만 선언한다 — platform·redirect_attempted 등 나머지는 무시
// (tolerant reader; 구버전 renderer 가 주던 title/price/source 도 같은 규칙으로 흡수된다).
// verdict 는 렌더 서비스의 판정: OK | BLOCK | EMPTY | ERROR — renderer 는 파싱하지 않으므로
// "가격 찾음" 여부에 결합하지 않는다(OK = html 확보, EMPTY = 렌더는 됐는데 html 없음).
//
// 전 필드 nullable(박싱 타입) — 렌더 서비스의 예외 격리 경로는 platform·verdict·error 만 싣는다. primitive 를
// 쓰면 Jackson 3(FAIL_ON_NULL_FOR_PRIMITIVES 기본 on)가 필드 부재를 역직렬화 실패로 만들어, verdict 번역에
// 닿기도 전에 일시 실패로 오분류된다.
@JsonIgnoreProperties(ignoreUnknown = true)
record HeadlessRenderResponse(
    String verdict,
    // 프록시 경유 여부 — 관측용.
    Boolean proxied,
    // 대상 페이지의 HTTP status — 관측용.
    Integer status,
    @JsonProperty("final_url") String finalUrl,
    String html,
    String error
) {
}
