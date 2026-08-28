package com.depromeet.piki.extractor.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProductSnapshotTest {

    private final ProductLink link = ProductLink.parse("https://shop.example.com/products/42");

    @Test
    @DisplayName("name 공백은 null 로 정규화된다")
    void blankNameToNull() {
        assertNull(ProductSnapshot.fromExtracted(link, "   ", null, 1_000, "KRW").name());
    }

    @Test
    @DisplayName("스킴을 갈아끼워 살릴 수 없는 imageUrl 만 null 로 정규화된다")
    void unusableImageUrlToNull() {
        List<String> cases = List.of(
            "data:image/png;base64,xxx",
            "javascript:alert(1)",
            "file:///etc/passwd",
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
    @DisplayName("http·프로토콜 상대 imageUrl 은 버리지 않고 https 로 올린다")
    void schemelessImageUrlUpgradedToHttps() {
        // og:image 에 http 를 적어 둔 몰이 실재한다. 버리면 이름·가격만 채운 INCOMPLETE 로 떨어지는데,
        // 그 값은 스킴만 올리면 그대로 쓸 수 있다(실측: 같은 주소가 http 301 -> https 200).
        assertEquals(
            "https://cdn.example.com/a.jpg",
            ProductSnapshot.fromExtracted(link, "상품", "http://cdn.example.com/a.jpg", 1_000, "KRW").imageUrl());
        assertEquals(
            "https://cdn.example.com/a.jpg",
            ProductSnapshot.fromExtracted(link, "상품", "//cdn.example.com/a.jpg", 1_000, "KRW").imageUrl());
    }

    @Test
    @DisplayName("스킴 대문자·혼합 표기도 https 로 올린다")
    void schemeUpgradeIsCaseInsensitive() {
        assertEquals(
            "https://cdn.example.com/a.jpg",
            ProductSnapshot.fromExtracted(link, "상품", "HTTP://cdn.example.com/a.jpg", 1_000, "KRW").imageUrl());
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

    @Test
    @DisplayName("READY 필수 필드(name·imageUrl·currentPrice)가 하나라도 비면 missingReadyField 다")
    void missingReadyFieldBranches() {
        String image = "https://cdn.example.com/a.jpg";
        assertTrue(new ProductSnapshot(link, null, image, 1_000, "KRW").missingReadyField());
        assertTrue(new ProductSnapshot(link, "   ", image, 1_000, "KRW").missingReadyField());
        assertTrue(new ProductSnapshot(link, "상품", null, 1_000, "KRW").missingReadyField());
        assertTrue(new ProductSnapshot(link, "상품", image, null, "KRW").missingReadyField());
        assertFalse(new ProductSnapshot(link, "상품", image, 1_000, null).missingReadyField());
    }
}
