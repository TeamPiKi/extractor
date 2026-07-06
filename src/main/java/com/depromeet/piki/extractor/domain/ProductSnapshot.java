package com.depromeet.piki.extractor.domain;

// PIKI-Server: product/service/ProductSnapshot.kt 포팅.
// 상품 추출 시점의 상태를 캡처한 결과. URL 추출(link)·이미지 추출(image) 두 경로가 공유하는 표현이며,
// 이미지 추출은 URL 이 없어 link 가 null 이다.
public record ProductSnapshot(
    ProductLink link,
    String name,
    String imageUrl,
    Integer currentPrice,
    String currency
) {

    // 컬럼 길이 제약은 호출자(PIKI-Server items 테이블)의 계약이다. 값이 바뀌면 양쪽을 함께 갱신한다.
    private static final int NAME_MAX_LENGTH = 512;
    private static final int IMAGE_URL_MAX_LENGTH = 2048;

    // 원시 추출값(구조화 파싱·LLM 추출이 공유)을 정규화·범위검증해 만드는 단일 진실 원천.
    // name blank→null, imageUrl 은 https 만(클라이언트가 <img src> 로 쓸 때의 XSS 사다리 차단),
    // currency 는 ISO 4217 로 정규화한다. 추출값이 컬럼 제약·상식을 벗어나면(가격 음수·길이 초과)
    // 추출 실패로 보고 untrustworthyValue 를 던진다.
    //
    // 실패 처리는 호출부가 고른다: 구조화 경로는 이 예외를 흡수해 Miss(INVALID_VALUE → LLM fallback)로,
    // LLM 경로는 그대로 흘려 확정 실패(422)로 떨어뜨린다. 같은 검증, 실패 표현만 다르다.
    public static ProductSnapshot fromExtracted(
        ProductLink link,
        String name,
        String imageUrl,
        Integer currentPrice,
        String currency
    ) {
        String normalizedName = normalizeBlankToNull(name);
        String normalizedImageUrl = normalizeImageUrl(imageUrl);
        String normalizedCurrency = CurrencyCode.normalizeOrNull(currency);

        if (currentPrice != null && currentPrice < 0) {
            throw ProductSnapshotException.untrustworthyValue();
        }
        if (normalizedName != null && normalizedName.length() > NAME_MAX_LENGTH) {
            throw ProductSnapshotException.untrustworthyValue();
        }
        if (normalizedImageUrl != null && normalizedImageUrl.length() > IMAGE_URL_MAX_LENGTH) {
            throw ProductSnapshotException.untrustworthyValue();
        }

        return new ProductSnapshot(link, normalizedName, normalizedImageUrl, currentPrice, normalizedCurrency);
    }

    private static String normalizeBlankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }

    private static String normalizeImageUrl(String imageUrl) {
        if (imageUrl == null || imageUrl.isBlank()) {
            return null;
        }
        if (!imageUrl.regionMatches(true, 0, "https://", 0, "https://".length())) {
            return null;
        }
        return imageUrl;
    }
}
