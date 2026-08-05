package com.depromeet.piki.extractor.image;

import com.depromeet.piki.extractor.image.domain.ProductImage;

/**
 * 이미지 추출 경계. 구현({@code GeminiProductImageExtractor})이 아니라 이 인터페이스에 의존하게 해
 * 통합 테스트가 외부 LLM 호출을 stub 으로 격리할 수 있다 (link 경로의 {@code GeminiClient} 와 같은 규약).
 */
public interface ProductImageExtractor {

    /**
     * @param model 호출자가 지정한 LLM 모델(없으면 null). 링크 경로와 축이 갈려 있어 이미지 지정만 여기로 온다 —
     *     이미지는 보는 능력이 필요해 링크에 맞는 모델이 여기서 맞지 않을 수 있다.
     */
    ImageExtraction extract(ProductImage image, String model);
}
