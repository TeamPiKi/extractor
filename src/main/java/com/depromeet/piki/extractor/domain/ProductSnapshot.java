package com.depromeet.piki.extractor.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * URL 추출·이미지 추출 두 경로가 공유하는 표현이라, 이미지 추출에는 원본 URL 이 없어 link·finalUrl 이 null 이다.
 * <p>finalUrl(리다이렉트를 따라간 최종 페이지 URL)은 호출자(core)가 상품 정체성(canonical) 정규화의 입력으로
 * 쓴다 — 단축링크는 경로가 불투명 코드라 이 값 없이는 같은 상품을 알아볼 수 없다.
 */
public record ProductSnapshot(
    ProductLink link,
    String name,
    String imageUrl,
    Integer currentPrice,
    String currency,
    ProductLink finalUrl,
    ExtractionMethod method
) {

    /**
     * 추출값 5필드만으로 만드는 편의 생성자. 값과 출처의 생산 시점이 달라서다 — 값은 파서·LLM(fromExtracted)이
     * 만들지만, finalUrl 과 method 는 그 바깥(파이프라인·이미지 서비스)만 안다. 출처는 withOrigin 으로 나중에 채운다.
     */
    public ProductSnapshot(ProductLink link, String name, String imageUrl, Integer currentPrice, String currency) {
        this(link, name, imageUrl, currentPrice, currency, null, null);
    }

    /**
     * 출처(귀결점·추출 경로)를 표기한 사본 — record 라 wither 로 채운다.
     * <p>method 는 표기 지점(파이프라인·이미지 서비스)이 항상 확정하므로 null 을 받지 않는다(놓치면 코드 버그).
     * finalUrl 은 이미지 경로에 원본 URL 이 없어 null 이 유효하다.
     */
    public ProductSnapshot withOrigin(ProductLink finalUrl, ExtractionMethod method) {
        Objects.requireNonNull(method, "method");
        return new ProductSnapshot(link, name, imageUrl, currentPrice, currency, finalUrl, method);
    }

    /**
     * 호출자(core)의 READY 불변식(name·imageUrl·currentPrice)을 채우지 못했는가 — currency 는 READY 필수가 아니다.
     * 응답 경계(ExtractionResponse)의 성공 게이트와 헤드리스 에스컬레이션 판정(FallbackProductLinkExtractor)이
     * 이 판정 하나를 공유한다 — 두 곳의 조건이 어긋나면 "승격 없이 확정 실패" 또는 "무의미한 승격"이 생긴다.
     */
    public boolean missingReadyField() {
        return name == null || name.isBlank() || imageUrl == null || currentPrice == null;
    }

    /**
     * 추출값을 하나도 못 얻었는가 — 호출자에게 내려보낼 것도, 사용자에게 "무엇을 채우라" 할 근거도 없는 상태다.
     *
     * <p>부분값(일부만 채움)은 호출자가 INCOMPLETE 로 수용해 사용자가 나머지를 채우므로(TeamPiKi/core#944)
     * 성공으로 내려보내고, 이 판정이 참일 때만 확정 실패로 닫는다. currency 는 READY 필수가 아니라 단독으로는
     * "건졌다"의 근거가 되지 못하므로 세지 않는다.
     */
    public boolean hasNoExtractedValue() {
        return (name == null || name.isBlank()) && imageUrl == null && currentPrice == null;
    }

    /**
     * 못 채운 READY 필드 이름들("currentPrice" · "name+currentPrice") — 무엇을 사용자에게 물어야 하는지를
     * 로그로 남기는 데 쓴다. 부분값을 성공으로 내려보내면 code 만으로는 어느 필드가 비었는지 사후 판별이 불가능하다.
     */
    public String missingFieldNames() {
        List<String> missing = new ArrayList<>();
        if (name == null || name.isBlank()) {
            missing.add("name");
        }
        if (imageUrl == null) {
            missing.add("imageUrl");
        }
        if (currentPrice == null) {
            missing.add("currentPrice");
        }
        return String.join("+", missing);
    }

    /** 컬럼 길이 제약은 호출자(core items 테이블)의 계약이다. 값이 바뀌면 양쪽을 함께 갱신한다. */
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
