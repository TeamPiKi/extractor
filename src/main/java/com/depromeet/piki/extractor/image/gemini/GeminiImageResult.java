package com.depromeet.piki.extractor.image.gemini;

import com.depromeet.piki.extractor.domain.CurrencyCode;
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
     * <p>주의: link 경로({@code GeminiExtractionResult})와 달리 {@code ProductSnapshot.fromExtracted} 를
     * 거치지 않고 직접 생성한다 — 그쪽 정규화를 여기서 태우지 않는다.
     */
    private ProductSnapshot toProductSnapshot() {
        return new ProductSnapshot(null, name, null, price, CurrencyCode.normalizeOrNull(currency));
    }
}
