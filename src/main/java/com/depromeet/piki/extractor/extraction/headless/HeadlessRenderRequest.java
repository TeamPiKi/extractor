package com.depromeet.piki.extractor.extraction.headless;

/**
 * POST /render 요청 wire 모델. 렌더 서비스(Python/FastAPI)의 snake_case 필드에 맞춘다.
 *
 * <p>authorized 는 "이 대상이 허락을 받았는가" 다 — 판단 없이 렌더 서비스에 그대로 넘긴다.
 * 이 필드를 모르는 구버전 renderer 는 무시하고 기본 동작으로 돌므로, 배포 순서와 무관하게 안전한 쪽으로만 어긋난다.
 *
 * <p>compress 를 모르는 구버전 renderer 는 이 필드를 무시하고(pydantic 기본) plain JSON 을 준다 — 그래서
 * 해제 판별을 요청이 아니라 응답 헤더로 두면(HttpHeadlessRenderer 참조) 켠 채로도 배포 순서와 무관하게 안전하다.
 */
record HeadlessRenderRequest(
    String url,
    boolean authorized,
    boolean compress
) {
}
