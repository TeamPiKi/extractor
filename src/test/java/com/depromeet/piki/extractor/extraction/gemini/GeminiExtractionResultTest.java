package com.depromeet.piki.extractor.extraction.gemini;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.depromeet.piki.extractor.domain.ProductLink;
import com.depromeet.piki.extractor.domain.ProductSnapshot;
import com.depromeet.piki.extractor.domain.ProductSnapshotException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GeminiExtractionResultTest {

    private final ProductLink link = ProductLink.parse("https://shop.example.com/products/42");

    @Test
    @DisplayName("isProductPage 가 false 이면 notProductPage 예외를 던진다")
    void notProductPageThrows() {
        GeminiExtractionResult result = new GeminiExtractionResult(false, null, null, null, null);

        assertThrows(ProductSnapshotException.class, () -> result.toProductSnapshot(link));
    }

    @Test
    @DisplayName("name 이 비어 있어도 ProductSnapshot 으로 변환되며 name 은 null 로 정규화된다")
    void blankNameNormalizedToNull() {
        GeminiExtractionResult result = new GeminiExtractionResult(true, "   ", null, null, null);

        ProductSnapshot product = result.toProductSnapshot(link);

        assertNull(product.name());
    }

    @Test
    @DisplayName("일부 필드만 있어도 ProductSnapshot 으로 변환된다")
    void partialFieldsAreMapped() {
        GeminiExtractionResult result = new GeminiExtractionResult(true, "테스트 상품", 10_000, null, null);

        ProductSnapshot product = result.toProductSnapshot(link);

        assertEquals("테스트 상품", product.name());
        assertEquals(10_000, product.currentPrice());
        assertNull(product.imageUrl());
    }

    @Test
    @DisplayName("전 필드가 채워진 경우 그대로 ProductSnapshot 에 매핑된다")
    void allFieldsAreMapped() {
        GeminiExtractionResult result =
            new GeminiExtractionResult(true, "나이키 에어포스", 99_000, "KRW", "https://cdn.example.com/p/42.jpg");

        ProductSnapshot product = result.toProductSnapshot(link);

        assertEquals("나이키 에어포스", product.name());
        assertEquals(99_000, product.currentPrice());
        assertEquals("KRW", product.currency());
        assertEquals("https://cdn.example.com/p/42.jpg", product.imageUrl());
        assertEquals(link, product.link());
    }

    @Test
    @DisplayName("비어 있는 문자열 필드는 null 로 정규화된다")
    void blankStringFieldsNormalizedToNull() {
        GeminiExtractionResult result = new GeminiExtractionResult(true, "테스트", null, null, "");

        ProductSnapshot product = result.toProductSnapshot(link);

        assertNull(product.imageUrl());
    }

    @Test
    @DisplayName("currentPrice 가 음수이면 ProductSnapshotException 을 던진다")
    void negativePriceThrows() {
        GeminiExtractionResult result = new GeminiExtractionResult(true, "테스트 상품", -100, null, null);

        assertThrows(ProductSnapshotException.class, () -> result.toProductSnapshot(link));
    }

    @Test
    @DisplayName("name 이 512자를 초과하면 ProductSnapshotException 을 던진다")
    void tooLongNameThrows() {
        GeminiExtractionResult result = new GeminiExtractionResult(true, "가".repeat(513), null, null, null);

        assertThrows(ProductSnapshotException.class, () -> result.toProductSnapshot(link));
    }

    @Test
    @DisplayName("imageUrl 이 2048자를 초과하면 ProductSnapshotException 을 던진다")
    void tooLongImageUrlThrows() {
        GeminiExtractionResult result =
            new GeminiExtractionResult(true, "테스트", null, null, "https://cdn.example.com/" + "a".repeat(2048));

        assertThrows(ProductSnapshotException.class, () -> result.toProductSnapshot(link));
    }

    @Test
    @DisplayName("ISO 4217 형식이 아닌 currency 는 예외 대신 null 로 정규화된다")
    void invalidCurrencyNormalizedToNull() {
        GeminiExtractionResult result = new GeminiExtractionResult(true, "테스트", 1_000, "ABCDEFGHI", null);

        assertNull(result.toProductSnapshot(link).currency());
    }

    @Test
    @DisplayName("currency 대소문자·공백은 ISO 4217 대문자로 정규화된다")
    void currencyIsNormalizedToUpperCase() {
        GeminiExtractionResult result = new GeminiExtractionResult(true, "테스트", 1_000, " usd ", null);

        assertEquals("USD", result.toProductSnapshot(link).currency());
    }
}
