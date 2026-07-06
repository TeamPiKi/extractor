package com.depromeet.piki.extractor.extraction;

import com.depromeet.piki.extractor.domain.ProductLink;
import com.depromeet.piki.extractor.domain.ProductSnapshot;

// PIKI-Server: product/service/ProductLinkExtractor.kt 포팅. URL 추출의 공개 진입점 계약 —
// API 컨트롤러는 이 인터페이스만 알고, 전략 구성(plain/headless)은 뒤에 숨는다.
public interface ProductLinkExtractor {

    ProductSnapshot extract(ProductLink link);
}
