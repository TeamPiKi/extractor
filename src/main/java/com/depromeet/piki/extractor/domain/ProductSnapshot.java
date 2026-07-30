package com.depromeet.piki.extractor.domain;

import java.util.Objects;

// 상품 추출 시점의 상태를 캡처한 결과. URL 추출(link)·이미지 추출(image) 두 경로가 공유하는 표현이며,
// 이미지 추출은 URL 이 없어 link 와 finalUrl 이 null 이다.
// finalUrl 은 리다이렉트를 따라간 최종 페이지 URL — 호출자(core)가 상품 정체성(canonical) 정규화의 입력으로
// 쓴다(core#825). 단축링크는 경로가 불투명 코드라 이 값 없이는 같은 상품을 알아볼 수 없다.
public record ProductSnapshot(
    ProductLink link,
    String name,
    String imageUrl,
    Integer currentPrice,
    String currency,
    ProductLink finalUrl,
    ExtractionMethod method
) {

    // 추출값 5필드만으로 만드는 편의 생성자. 값과 출처의 생산 시점이 달라서다 — 값은 파서·LLM(fromExtracted)이
    // 만들지만, finalUrl 과 method 는 그 바깥(파이프라인·이미지 서비스)만 안다. 출처는 withOrigin 으로 나중에 채운다.
    public ProductSnapshot(ProductLink link, String name, String imageUrl, Integer currentPrice, String currency) {
        this(link, name, imageUrl, currentPrice, currency, null, null);
    }

    // 출처(귀결점·추출 경로)를 표기한 사본 — record 라 wither 로 채운다.
    // method 는 표기 지점(파이프라인·이미지 서비스)이 항상 확정하므로 null 을 받지 않는다(놓치면 코드 버그).
    // finalUrl 은 이미지 경로에 원본 URL 이 없어 null 이 유효하다.
    public ProductSnapshot withOrigin(ProductLink finalUrl, ExtractionMethod method) {
        Objects.requireNonNull(method, "method");
        return new ProductSnapshot(link, name, imageUrl, currentPrice, currency, finalUrl, method);
    }

    // 컬럼 길이 제약은 호출자(core items 테이블)의 계약이다. 값이 바뀌면 양쪽을 함께 갱신한다.
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
