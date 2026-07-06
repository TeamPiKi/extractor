package com.depromeet.piki.extractor.extraction;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

// PIKI-Server: product/service/HeadlessExtractionProperties.kt 포팅.
// 헤드리스(차단 우회) 추출 설정. 초기 헤드리스 연동(이관 7단계)은 PIKI-HeadlessBrowser 의 REST API 호출 하나로
// 확정이다(엔드포인트 1개). 응답 형태는 아직 미확정이라 매핑은 구현 때 정하고, url·timeout 등 필드도 그때
// 함께 추가한다(소비자 없는 미사용 필드를 미리 만들지 않는다).
@ConfigurationProperties(prefix = "product.extract.headless")
public record HeadlessExtractionProperties(
    // 헤드리스 fallback 스위치. 기본 false — 구현이 붙기 전엔 꺼 둬야 하며,
    // 켜면 escalatable 차단 링크가 HeadlessProductLinkExtractor 로 흐른다.
    @DefaultValue("false") boolean enabled
) {
}
