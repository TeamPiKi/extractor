package com.depromeet.piki.extractor.domain;

// 값을 만들어낸 추출 경로. 호출자(core)가 snapshot 출처를 구분 저장하는 근거다(core#825 결정 4) —
// LLM 경로는 같은 페이지를 재추출해도 값이 달라질 수 있어(비결정성 실측), 이후 "LLM 추출분은 가격 변동
// 알림 제외" 같은 신뢰 정책이 이 구분 위에서 갈린다. 응답에는 enum 이름 문자열로 나간다(계약 §2).
public enum ExtractionMethod {
    // 구조화 데이터(JSON-LD·OpenGraph) 직접 파싱 — 결정론적.
    STRUCTURED,
    // Gemini LLM 추출 — URL 경로의 fallback 과 이미지 경로가 여기 속한다.
    LLM,
}
