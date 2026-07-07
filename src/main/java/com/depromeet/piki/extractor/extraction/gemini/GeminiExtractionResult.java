package com.depromeet.piki.extractor.extraction.gemini;

import com.depromeet.piki.extractor.domain.ProductLink;
import com.depromeet.piki.extractor.domain.ProductSnapshot;
import com.depromeet.piki.extractor.domain.ProductSnapshotException;

public record GeminiExtractionResult(
    boolean isProductPage,
    String name,
    Integer currentPrice,
    String currency,
    String imageUrl
) {

    // LLM 고유의 "상품 페이지인가" 판정만 여기서 하고, 정규화·범위검증은 ProductSnapshot.fromExtracted 에 위임한다.
    // (구조화 파싱 경로와 같은 검증을 공유하는 single source.)
    public ProductSnapshot toProductSnapshot(ProductLink link) {
        if (!isProductPage) {
            throw ProductSnapshotException.notProductPage();
        }
        return ProductSnapshot.fromExtracted(link, name, imageUrl, currentPrice, currency);
    }
}
