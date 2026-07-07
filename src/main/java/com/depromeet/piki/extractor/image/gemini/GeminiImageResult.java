package com.depromeet.piki.extractor.image.gemini;

import com.depromeet.piki.extractor.domain.CurrencyCode;
import com.depromeet.piki.extractor.domain.ProductSnapshot;
import com.depromeet.piki.extractor.image.ImageExtraction;
import com.depromeet.piki.extractor.image.domain.BoundingBox;

// Gemini 가 responseSchema(PRODUCT_SCHEMA)에 따라 생성하는 JSON 을 역직렬화한 결과. 단일 상품만 받는 OBJECT 스키마다.
public record GeminiImageResult(
    String name,
    Integer price,
    String category,
    String currency,
    BoundingBoxDto boundingBox
) {

    // Gemini 가 normalized 좌표(0~1000)로 주는 상품 영역. 네 좌표가 한 객체로 묶여 온다.
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

    // 이미지 추출 결과를 URL 추출과 같은 ProductSnapshot 으로 옮긴다. URL 이 없어 link 는 null,
    // 이미지 URL 은 추출 시점엔 없어 imageUrl 도 null(업로드 후 오케스트레이터가 채운다),
    // category 는 현재 item 모델에 없어 버린다. price → currentPrice 로 어휘를 통일한다.
    // currency 는 LLM 이 형식을 제각각 주므로 ISO 4217 로 정규화하고, 안 맞으면 null.
    //
    // 주의: link 경로(GeminiExtractionResult)와 달리 ProductSnapshot.fromExtracted 를 거치지 않고
    // 직접 생성한다 — 정규화(name blank→null·길이·가격 음수 검증)를 여기서 태우지 않는다.
    private ProductSnapshot toProductSnapshot() {
        return new ProductSnapshot(null, name, null, price, CurrencyCode.normalizeOrNull(currency));
    }
}
