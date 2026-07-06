package com.depromeet.piki.extractor.extraction;

import com.depromeet.piki.extractor.domain.ProductLink;

// PIKI-Server: product/service/PageFetcher.kt 포팅. 외부(대상 몰) HTTP 경계 — 테스트 stub 지점.
public interface PageFetcher {

    PageContent fetch(ProductLink link);
}
