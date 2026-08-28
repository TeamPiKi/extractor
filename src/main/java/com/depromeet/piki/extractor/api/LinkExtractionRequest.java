package com.depromeet.piki.extractor.api;

/**
 * POST /internal/extractions/link 요청 (docs/api-contract.md §2).
 *
 * <p>url 형식 검증을 Bean Validation 이 아니라 {@code ProductLink.parse} 에 맡긴다 — blank·형식·스킴
 * 위반이 전부 계약 코드 INVALID_URL 하나로 떨어져야 하기 때문이다.
 *
 * <p>authorized 를 primitive boolean 이 아니라 Boolean 으로 받는 이유: Jackson 3 는
 * FAIL_ON_NULL_FOR_PRIMITIVES 가 기본 on 이라, 이 선택 필드를 안 보내는 구버전 호출자의 요청이 400 으로
 * 깨진다(통합 테스트로 실측).
 *
 * <p>authorized 는 "이 대상이 플랫폼의 명시적 허락을 받았는가"다. 누락 정규화가 false 인 것은 fail-safe 다 —
 * 이 필드를 모르는 구버전 호출자의 요청은 "허락 없음"으로 떨어져 우회 수단이 열리지 않는다. 판정의 원장은
 * 호출자(core)에 있고 이 서비스는 요청 단위로 받아 렌더 서비스까지 전달만 한다.
 *
 * <p>model 은 호출자가 지정한 LLM 모델이며 선택 필드다. 안 보내면 null 이 되어 기본 모델을 쓴다(String 이라
 * primitive 함정은 없다). 빈 문자열·공백 처리는 GeminiHttpClient 의 후보 계산이 흡수한다.
 */
public record LinkExtractionRequest(String url, Boolean authorized, String model) {

    public LinkExtractionRequest {
        authorized = Boolean.TRUE.equals(authorized);
    }
}
