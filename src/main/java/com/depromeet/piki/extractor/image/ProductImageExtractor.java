package com.depromeet.piki.extractor.image;

import com.depromeet.piki.extractor.image.domain.ProductImage;

// 이미지 OCR 추출 경계. 구현(GeminiProductImageExtractor)이 아니라 이 인터페이스에 의존하게 해
// 통합 테스트가 외부 LLM 호출을 stub 으로 격리할 수 있다 (link 경로의 GeminiClient 와 같은 규약).
public interface ProductImageExtractor {

    ImageExtraction extract(ProductImage image);
}
