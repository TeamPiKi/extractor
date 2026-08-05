package com.depromeet.piki.extractor.api;

import com.depromeet.piki.extractor.probe.ProbeTarget;

/**
 * POST /internal/models/probe 요청 (docs/api-contract.md §2).
 *
 * <p>두 필드 모두 필수다. 추출 요청의 model 과 달리 "지정 없음"이 의미를 갖지 않는다 — 무엇을 확인할지 모르면
 * 프로브 자체가 성립하지 않기 때문이다. 검증은 컨트롤러가 진다.
 */
public record ModelProbeRequest(String model, ProbeTarget target) {
}
