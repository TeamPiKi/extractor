package com.depromeet.piki.extractor.image;

import com.depromeet.piki.extractor.domain.ProductSnapshot;
import com.depromeet.piki.extractor.image.domain.BoundingBox;

/**
 * boundingBox 는 박스를 못 잡았거나 비정상이면 null — 크롭을 건너뛰고 imageUrl 을 원본으로 채우라는 신호다
 * ({@code BoundingBox.ofNormalizedOrNull} 의 "없으면 null" 규약이라 컴포넌트가 nullable 이다).
 */
public record ImageExtraction(
    ProductSnapshot snapshot,
    BoundingBox boundingBox
) {
}
