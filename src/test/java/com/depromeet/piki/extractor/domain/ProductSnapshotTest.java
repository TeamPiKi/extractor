package com.depromeet.piki.extractor.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// PIKI-Server: product/service/ProductSnapshotTest.kt 포팅 (fromExtracted 정규화·범위검증 분기).
class ProductSnapshotTest {

    private final ProductLink link = ProductLink.parse("https://shop.example.com/products/42");

    @Test
    @DisplayName("name 공백은 null 로 정규화된다")
    void blankNameToNull() {
        assertNull(ProductSnapshot.fromExtracted(link, "   ", null, 1_000, "KRW").name());
    }

    @Test
    @DisplayName("imageUrl 은 https 가 아니면 null 로 정규화된다")
    void nonHttpsImageUrlToNull() {
        List<String> cases = List.of(
            "http://cdn.example.com/a.jpg",
            "//cdn.example.com/a.jpg",
            "data:image/png;base64,xxx",
            "javascript:alert(1)",
            "");
        for (String raw : cases) {
            assertNull(
                ProductSnapshot.fromExtracted(link, "상품", raw, 1_000, "KRW").imageUrl(),
                "'" + raw + "' 는 거부되어야 함");
        }
    }

    @Test
    @DisplayName("https imageUrl 은 그대로 통과한다")
    void httpsImageUrlPasses() {
        assertEquals(
            "https://cdn.example.com/a.jpg",
            ProductSnapshot.fromExtracted(link, "상품", "https://cdn.example.com/a.jpg", 1_000, "KRW").imageUrl());
    }

    @Test
    @DisplayName("currency 대소문자·공백은 ISO 4217 대문자로 정규화된다")
    void currencyNormalized() {
        assertEquals("USD", ProductSnapshot.fromExtracted(link, "상품", null, 1_000, " usd ").currency());
    }

    @Test
    @DisplayName("ISO 4217 이 아닌 currency 는 null 로 정규화된다")
    void nonIso4217CurrencyToNull() {
        assertNull(ProductSnapshot.fromExtracted(link, "상품", null, 1_000, "ZZZ").currency());
    }

    @Test
    @DisplayName("가격이 음수면 ProductSnapshotException 을 던진다")
    void negativePriceThrows() {
        assertThrows(
            ProductSnapshotException.class,
            () -> ProductSnapshot.fromExtracted(link, "상품", null, -1, "KRW"));
    }

    @Test
    @DisplayName("name 이 512자를 초과하면 ProductSnapshotException 을 던진다")
    void tooLongNameThrows() {
        assertThrows(
            ProductSnapshotException.class,
            () -> ProductSnapshot.fromExtracted(link, "가".repeat(513), null, 1_000, "KRW"));
    }

    @Test
    @DisplayName("imageUrl 이 2048자를 초과하면 ProductSnapshotException 을 던진다")
    void tooLongImageUrlThrows() {
        assertThrows(
            ProductSnapshotException.class,
            () -> ProductSnapshot.fromExtracted(link, "상품", "https://cdn.example.com/" + "a".repeat(2048), 1_000, "KRW"));
    }

    @Test
    @DisplayName("link 가 null 이어도(이미지 추출 경로) 변환된다")
    void nullLinkConverts() {
        ProductSnapshot snapshot = ProductSnapshot.fromExtracted(null, "상품", null, 1_000, "KRW");
        assertNull(snapshot.link());
        assertEquals("상품", snapshot.name());
    }

    @Test
    @DisplayName("currentPrice 가 null 이면 예외 없이 null 로 통과한다")
    void nullPricePasses() {
        assertNull(ProductSnapshot.fromExtracted(link, "상품", null, null, "KRW").currentPrice());
    }

    @Test
    @DisplayName("currentPrice 가 0 이면 0 으로 통과한다")
    void zeroPricePasses() {
        assertEquals(0, ProductSnapshot.fromExtracted(link, "상품", null, 0, "KRW").currentPrice());
    }

    @Test
    @DisplayName("currentPrice 가 양수이면 그대로 통과한다")
    void positivePricePasses() {
        assertEquals(1_000, ProductSnapshot.fromExtracted(link, "상품", null, 1_000, "KRW").currentPrice());
    }
}
