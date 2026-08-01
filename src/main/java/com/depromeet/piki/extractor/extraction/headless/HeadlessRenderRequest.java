package com.depromeet.piki.extractor.extraction.headless;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * POST /render 요청 wire 모델. 렌더 서비스(Python/FastAPI)의 snake_case 필드에 맞춘다.
 *
 * <p>include_html 을 켜는 이유: renderer 는 파싱하지 않으므로(HTML 렌더러) 파싱(구조화/LLM)은 우리가 렌더된
 * HTML 로 직접 한다.
 *
 * <p>compress 를 모르는 구버전 renderer 는 이 필드를 무시하고(pydantic 기본) plain JSON 을 준다 — 그래서
 * 해제 판별을 요청이 아니라 응답 헤더로 두면(HttpHeadlessRenderer 참조) 켠 채로도 배포 순서와 무관하게 안전하다.
 */
record HeadlessRenderRequest(
    String url,
    @JsonProperty("include_html") boolean includeHtml,
    boolean compress
) {
}
