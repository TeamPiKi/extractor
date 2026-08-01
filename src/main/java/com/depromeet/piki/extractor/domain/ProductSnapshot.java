package com.depromeet.piki.extractor.domain;

/** URL 추출·이미지 추출 두 경로가 공유하는 표현이라, 이미지 추출에는 원본 URL 이 없어 link 가 null 이다. */
public record ProductSnapshot(
    ProductLink link,
    String name,
    String imageUrl,
    Integer currentPrice,
    String currency
) {

    /** 컬럼 길이 제약은 호출자(PIKI-Server items 테이블)의 계약이다. 값이 바뀌면 양쪽을 함께 갱신한다. */
    private static final int NAME_MAX_LENGTH = 512;
    private static final int IMAGE_URL_MAX_LENGTH = 2048;

    /**
     * 구조화 파싱과 LLM 추출이 함께 통과하는 정규화·범위검증의 단일 진실 원천.
     * imageUrl 을 https 로만 좁히는 것은 클라이언트가 {@code <img src>} 로 쓸 때의 XSS 사다리를 끊기 위한 것이다.
     * <p>범위를 벗어난 값은 {@link ProductSnapshotException#untrustworthyValue()} 로 막고, 그 뒤 처리는 호출부가 고른다:
     * 구조화 경로는 예외를 흡수해 Miss(LLM fallback)로, LLM 경로는 그대로 흘려 확정 실패로 떨어뜨린다.
     * 같은 검증, 실패 표현만 다르다.
     */
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
