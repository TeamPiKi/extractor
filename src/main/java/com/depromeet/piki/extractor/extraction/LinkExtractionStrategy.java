package com.depromeet.piki.extractor.extraction;

import com.depromeet.piki.extractor.domain.ProductLink;
import com.depromeet.piki.extractor.domain.ProductSnapshot;

/**
 * 상품 URL 추출의 "한 전략" — plain(정적 HTTP fetch)과 headless(차단 우회 브라우저)가 이 계약을 구현한다.
 *
 * <p>공개 진입점({@link ProductLinkExtractor})과 분리한 이유: API 계층은 진입점만 알아, 전략이 늘거나 바뀌어도
 * 영향받지 않는다.
 */
public interface LinkExtractionStrategy {

    /**
     * 전략 빈의 명시 이름 = 단일 진실. 클래스명 기본값(decapitalized)에 맡기면 클래스 rename 시
     * {@code @Qualifier} 문자열이 조용히 어긋나므로, 클래스명과 무관한 상수로 못박는다.
     */
    String PLAIN = "plainLinkExtractionStrategy";
    String HEADLESS = "headlessLinkExtractionStrategy";

    /**
     * @param authorized 이 대상이 허락을 받았는가. 정적 fetch 전략에는 우회 개념이 없어 무시되고, 헤드리스
     *     전략만 렌더 서비스로 전달한다. 두 전략이 같은 시그니처를 갖는 편이 호출부(Fallback)가 분기를
     *     들고 있는 것보다 낫다 — 전략이 늘어도 호출부가 안 바뀐다.
     * @param model 호출자가 지정한 LLM 모델(없으면 null). 전략은 해석하지 않고 파이프라인까지 흘려보낸다.
     */
    ProductSnapshot extract(ProductLink link, boolean authorized, String model);
}
