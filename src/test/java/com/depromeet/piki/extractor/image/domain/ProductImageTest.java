package com.depromeet.piki.extractor.image.domain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ProductImageTest {

    @ParameterizedTest
    @ValueSource(strings = {"image/png", "image/jpeg", "image/webp", "image/heic", "image/heif"})
    @DisplayName("지원하는 MIME 타입이면 ProductImage 가 생성된다")
    void createsForSupportedMimeType(String mimeType) {
        byte[] bytes = {1, 2, 3};

        ProductImage image = ProductImage.of(bytes, mimeType);

        assertEquals(mimeType, image.mimeType());
        assertArrayEquals(bytes, image.bytes());
    }

    @ParameterizedTest
    @ValueSource(strings = {"IMAGE/JPEG", "Image/Png", "image/jpeg; charset=utf-8", "  image/webp  "})
    @DisplayName("대소문자·파라미터가 섞인 MIME 타입도 정규화되어 생성된다")
    void normalizesMixedCaseAndParameterMimeType(String mimeType) {
        ProductImage image = ProductImage.of(new byte[] {1, 2, 3}, mimeType);

        int semicolon = mimeType.indexOf(';');
        String beforeSemicolon = semicolon >= 0 ? mimeType.substring(0, semicolon) : mimeType;
        String expected = beforeSemicolon.trim().toLowerCase(Locale.ROOT);
        assertEquals(expected, image.mimeType());
    }

    @Test
    @DisplayName("생성에 쓰인 원본 배열을 변경해도 ProductImage 내부는 영향받지 않는다")
    void defensiveCopyOfSourceArray() {
        byte[] original = {1, 2, 3};
        ProductImage image = ProductImage.of(original, "image/png");

        original[0] = 99;

        assertArrayEquals(new byte[] {1, 2, 3}, image.bytes());
    }

    @Test
    @DisplayName("bytes 로 반환된 배열을 변경해도 ProductImage 내부는 영향받지 않는다")
    void defensiveCopyOfReturnedArray() {
        ProductImage image = ProductImage.of(new byte[] {1, 2, 3}, "image/png");

        image.bytes()[0] = 99;

        assertArrayEquals(new byte[] {1, 2, 3}, image.bytes());
    }

    @Test
    @DisplayName("빈 바이트 배열이면 예외가 발생한다")
    void emptyBytesThrows() {
        assertThrows(ProductImageException.class, () -> ProductImage.of(new byte[0], "image/png"));
    }

    @Test
    @DisplayName("MIME 타입이 null 이면 예외가 발생한다")
    void nullMimeTypeThrows() {
        assertThrows(ProductImageException.class, () -> ProductImage.of(new byte[] {1}, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"image/gif", "image/bmp", "image/svg+xml", "application/pdf", "text/plain", ""})
    @DisplayName("지원하지 않는 MIME 타입이면 예외가 발생한다")
    void unsupportedMimeTypeThrows(String mimeType) {
        assertThrows(ProductImageException.class, () -> ProductImage.of(new byte[] {1}, mimeType));
    }

    @ParameterizedTest
    @CsvSource({
        "image/png, png",
        "image/jpeg, jpg",
        "image/webp, webp",
        "image/heic, heic",
        "image/heif, heif",
    })
    @DisplayName("MIME 타입에서 스토리지 object key 확장자를 도출한다")
    void derivesStorageExtensionFromMimeType(String mimeType, String expected) {
        assertEquals(expected, ProductImage.of(new byte[] {1}, mimeType).extension());
    }

    @ParameterizedTest
    @CsvSource({
        "image/png, png",
        "image/jpeg, jpg",
        "image/webp, webp",
        "image/heic, heic",
        "image/heif, heif",
    })
    @DisplayName("content-type 만으로 바이트 없이 스토리지 확장자를 도출한다")
    void derivesStorageExtensionWithoutBytes(String mimeType, String expected) {
        // 이미지 등록 v2 의 발급 단계는 바이트가 아직 없어 content-type 만으로 raw key 확장자를 만든다.
        assertEquals(expected, ProductImage.extensionForMimeType(mimeType));
    }

    @ParameterizedTest
    @CsvSource({
        "IMAGE/JPEG, jpg",
        "Image/Png, png",
        "image/webp; charset=utf-8, webp",
        "'  image/heic  ', heic",
    })
    @DisplayName("extensionForMimeType 도 of 와 같은 정규화를 거쳐 정확한 확장자를 도출한다")
    void extensionForMimeTypeNormalizes(String mimeType, String expected) {
        assertEquals(expected, ProductImage.extensionForMimeType(mimeType));
    }

    @ParameterizedTest
    @ValueSource(strings = {"image/gif", "image/svg+xml", "application/pdf", "text/plain", ""})
    @DisplayName("extensionForMimeType 은 지원하지 않는 타입이면 예외가 발생한다")
    void extensionForMimeTypeUnsupportedThrows(String mimeType) {
        assertThrows(ProductImageException.class, () -> ProductImage.extensionForMimeType(mimeType));
    }

    @Test
    @DisplayName("extensionForMimeType 은 content-type 이 null 이면 예외가 발생한다")
    void extensionForMimeTypeNullThrows() {
        assertThrows(ProductImageException.class, () -> ProductImage.extensionForMimeType(null));
    }
}
