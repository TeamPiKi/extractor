package com.depromeet.piki.extractor.image.gemini;

import com.depromeet.piki.extractor.domain.ProductSnapshot;
import com.depromeet.piki.extractor.image.ImageExtraction;
import com.depromeet.piki.extractor.image.domain.BoundingBox;

/**
 * Gemini 가 {@code GeminiImageRequest} 의 responseSchema 에 따라 생성하는 JSON 을 역직렬화한 결과 —
 * 단일 상품만 받는 OBJECT 스키마다.
 */
public record GeminiImageResult(
    String name,
    Integer price,
    String category,
    String currency,
    BoundingBoxDto boundingBox
) {

    public record BoundingBoxDto(
        Integer yMin,
        Integer xMin,
        Integer yMax,
        Integer xMax
    ) {}

    public ImageExtraction toImageExtraction() {
        BoundingBox box = boundingBox == null
            ? null
            : BoundingBox.ofNormalizedOrNull(boundingBox.yMin(), boundingBox.xMin(), boundingBox.yMax(), boundingBox.xMax());
        return new ImageExtraction(toProductSnapshot(), box);
    }

    /**
     * link 는 URL 이 없어 null, imageUrl 은 추출 시점엔 없어 null(업로드 후 오케스트레이터가 채운다),
     * category 는 현재 item 모델에 없어 버린다.
     *
     * <p>link 경로({@code GeminiExtractionResult})와 같은 {@code fromExtracted} 를 태운다 — 같은 LLM 이 만든
     * 값인데 경로에 따라 검증이 갈리면, 음수 가격·공백 이름 같은 값이 이미지 경로로만 호출자에게 새어 나간다.
     */
    private ProductSnapshot toProductSnapshot() {
        return ProductSnapshot.fromExtracted(null, name, null, price, currency);
    }
}
