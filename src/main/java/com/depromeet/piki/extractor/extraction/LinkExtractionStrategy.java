package com.depromeet.piki.extractor.extraction;

import com.depromeet.piki.extractor.domain.ProductLink;
import com.depromeet.piki.extractor.domain.ProductSnapshot;

// PIKI-Server: product/service/LinkExtractionStrategy.kt 포팅.
// 상품 URL 추출의 "한 전략". 두 구현:
//   - DefaultProductLinkExtractor  : 정적 HTTP fetch + 구조화(JSON-LD/OG) 우선 + LLM fallback (싸고 빠른 기본 경로)
//   - HeadlessProductLinkExtractor : 차단 우회 헤드리스 브라우저 (차단 플랫폼용, 이관 7단계까지 placeholder)
//
// 공개 진입점(ProductLinkExtractor)과 분리한다: API 계층은 진입점만 알고, 전략이 늘거나 바뀌어도 영향받지 않는다.
// FallbackProductLinkExtractor(진입점 구현)가 이 전략들을 "plain 먼저, 막히면 headless" 로 엮는다.
public interface LinkExtractionStrategy {

    // 전략 빈의 명시 이름 = 단일 진실. FallbackProductLinkExtractor 가 @Qualifier 로 이 이름을 참조해 두 전략을
    // 정확히 주입한다. 클래스명 기본값(decapitalized)에 맡기면 rename 시 @Qualifier 문자열이 조용히 어긋나므로
    // 클래스명과 무관한 상수로 못박는다 (rename-safe + single-source).
    String PLAIN = "plainLinkExtractionStrategy";
    String HEADLESS = "headlessLinkExtractionStrategy";

    ProductSnapshot extract(ProductLink link);
}
