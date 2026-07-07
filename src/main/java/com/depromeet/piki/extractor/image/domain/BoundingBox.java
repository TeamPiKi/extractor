package com.depromeet.piki.extractor.image.domain;

// Gemini 가 반환하는 상품 영역 박스. 좌표는 0~1000 normalized (Gemini 표준 [ymin, xmin, ymax, xmax]).
// 실제 픽셀 환산·크롭은 ImageCropper 가 원본 이미지 크기에 맞춰 수행한다.
public record BoundingBox(
    int yMin,
    int xMin,
    int yMax,
    int xMax
) {

    public static final int NORMALIZED_MAX = 1000;

    // 불변식 — 정상 흐름은 ofNormalizedOrNull 이 먼저 거른다. 직접 생성으로 비정상 좌표가
    // 들어오면 크롭이 조용히 어긋나므로 생성 시점에 막는다.
    public BoundingBox {
        if (!inRange(yMin) || !inRange(xMin) || !inRange(yMax) || !inRange(xMax)) {
            throw new IllegalArgumentException(
                "좌표는 0~" + NORMALIZED_MAX + " 범위여야 한다: (" + yMin + ", " + xMin + ", " + yMax + ", " + xMax + ")");
        }
        if (yMax <= yMin || xMax <= xMin) {
            throw new IllegalArgumentException(
                "max 는 min 보다 커야 한다: (" + yMin + ", " + xMin + ", " + yMax + ", " + xMax + ")");
        }
    }

    // 네 좌표가 모두 있고 0~1000 범위에 정상 순서(min < max)일 때만 만든다.
    // 하나라도 빠지거나 비정상이면 null — 크롭을 건너뛰는 신호다 (CurrencyCode.normalizeOrNull 과 같은 "OrNull" 규약).
    public static BoundingBox ofNormalizedOrNull(Integer yMin, Integer xMin, Integer yMax, Integer xMax) {
        if (yMin == null || xMin == null || yMax == null || xMax == null) {
            return null;
        }
        if (!inRange(yMin) || !inRange(xMin) || !inRange(yMax) || !inRange(xMax)) {
            return null;
        }
        if (yMax <= yMin || xMax <= xMin) {
            return null;
        }
        return new BoundingBox(yMin, xMin, yMax, xMax);
    }

    private static boolean inRange(int value) {
        return value >= 0 && value <= NORMALIZED_MAX;
    }
}
