package com.depromeet.piki.extractor.image;

import com.depromeet.piki.extractor.domain.ProductSnapshot;
import com.depromeet.piki.extractor.image.domain.BoundingBox;

// 이미지 추출 결과. 상품 정보(snapshot)와 크롭에 쓸 상품 영역(boundingBox)을 함께 담는다.
// boundingBox 는 박스를 못 잡았거나 비정상이면 null — 이 경우 크롭을 건너뛰고 imageUrl 은 원본으로 채운다
// (BoundingBox.ofNormalizedOrNull 과 같은 "없으면 null" 규약이라 record 컴포넌트가 nullable 이다).
public record ImageExtraction(
    ProductSnapshot snapshot,
    BoundingBox boundingBox
) {
}
