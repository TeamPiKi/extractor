package com.depromeet.piki.extractor.extraction.headless;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * POST /render 응답 wire 모델. renderer 는 판단하지 않고 거쳐 간 홉을 그대로 돌려준다 — 어느 홉이 상품이고
 * 차단인지는 우리가 정한다.
 *
 * <p>전 필드 nullable(박싱 타입) — 브라우저 예외 격리 경로는 error 만 싣는다. primitive 면 Jackson 3 가 필드 부재를
 * 역직렬화 실패로 만들어 계약 번역에 닿기도 전에 일시 실패로 오분류된다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record HeadlessRenderResponse(
    Boolean proxied,
    String error,
    List<Hop> hops,
    /**
     * 홉 계약 이전 renderer 의 필드. 그쪽 배포가 수동이라 뒤처지는 동안 홉 하나로 읽는다.
     * 제거 조건: renderer 홉 계약(#34) 배포 완료.
     */
    String verdict,
    String html,
    @JsonProperty("final_url") String finalUrl,
    Integer status
) {

    private static final String LEGACY_BLOCK = "BLOCK";

    boolean legacyBlocked() {
        return (hops == null || hops.isEmpty()) && LEGACY_BLOCK.equals(verdict);
    }

    List<Hop> hopsOrLegacy() {
        if (hops != null && !hops.isEmpty()) {
            return hops;
        }
        if (status == null && (html == null || html.isBlank())) {
            return List.of();
        }
        return List.of(new Hop(finalUrl, status, Map.of(), "", html));
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Hop(
        String url,
        Integer status,
        Map<String, String> headers,
        String body,
        String dom
    ) {
    }
}
