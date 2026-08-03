package com.depromeet.piki.extractor.image.gemini;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.depromeet.piki.extractor.domain.ProductSnapshot;
import com.depromeet.piki.extractor.domain.ProductSnapshotException;
import com.depromeet.piki.extractor.image.domain.BoundingBox;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class GeminiImageResultTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ---------- toProductSnapshot 매핑 ----------

    @Test
    @DisplayName("toProductSnapshot 은 link 없이 name·price·currency 를 옮기고 category 는 버린다")
    void mapsFieldsWithoutLinkAndDropsCategory() {
        GeminiImageResult result = new GeminiImageResult("우유", 3500, "음료", "KRW", null);

        ProductSnapshot snapshot = result.toImageExtraction().snapshot();

        assertNull(snapshot.link());
        assertEquals("우유", snapshot.name());
        assertEquals(3500, snapshot.currentPrice());
        assertEquals("KRW", snapshot.currency());
        assertNull(snapshot.imageUrl());
    }

    @Test
    @DisplayName("toProductSnapshot 은 null 필드를 그대로 옮긴다")
    void mapsNullFields() {
        GeminiImageResult result = new GeminiImageResult(null, null, null, null, null);

        ProductSnapshot snapshot = result.toImageExtraction().snapshot();

        assertNull(snapshot.name());
        assertNull(snapshot.currentPrice());
        assertNull(snapshot.currency());
    }

    @Test
    @DisplayName("toProductSnapshot 은 currency 대소문자·공백을 ISO 4217 로 정규화한다")
    void normalizesCurrency() {
        GeminiImageResult result = new GeminiImageResult("우유", 3500, "음료", " krw ", null);

        assertEquals("KRW", result.toImageExtraction().snapshot().currency());
    }

    @Test
    @DisplayName("toProductSnapshot 은 ISO 4217 형식이 아닌 currency 를 null 로 떨어뜨린다")
    void dropsInvalidCurrency() {
        GeminiImageResult result = new GeminiImageResult("우유", 3500, "음료", "원", null);

        assertNull(result.toImageExtraction().snapshot().currency());
    }

    // ---------- boundingBox 매핑 ----------

    @Test
    @DisplayName("toImageExtraction 은 정상 좌표 boundingBox 를 매핑한다")
    void mapsValidBoundingBox() {
        GeminiImageResult result = new GeminiImageResult(
            "우유", 3500, null, null,
            new GeminiImageResult.BoundingBoxDto(100, 200, 800, 700));

        BoundingBox bbox = result.toImageExtraction().boundingBox();

        assertNotNull(bbox);
        assertEquals(100, bbox.yMin());
        assertEquals(200, bbox.xMin());
        assertEquals(800, bbox.yMax());
        assertEquals(700, bbox.xMax());
    }

    @Test
    @DisplayName("toImageExtraction 은 boundingBox 가 없거나 비정상이면 null 이다")
    void nullBoundingBoxWhenMissingOrInvalid() {
        GeminiImageResult noBox = new GeminiImageResult("우유", 3500, null, null, null);
        assertNull(noBox.toImageExtraction().boundingBox());

        // yMax < yMin (순서 역전) → null
        GeminiImageResult invalid = new GeminiImageResult(
            "우유", 3500, null, null,
            new GeminiImageResult.BoundingBoxDto(800, 200, 100, 700));
        assertNull(invalid.toImageExtraction().boundingBox());
    }

    // ---------- wire format 역직렬화 ----------

    @Test
    @DisplayName("GeminiImageResult 를 wire format JSON 에서 역직렬화할 수 있다")
    void deserializesFromWireFormat() {
        String json = "{\"name\": \"우유\", \"price\": 3500, \"category\": \"음료\", \"currency\": \"KRW\"}";

        GeminiImageResult result = objectMapper.readValue(json, GeminiImageResult.class);

        assertEquals("우유", result.name());
        assertEquals(3500, result.price());
        assertEquals("음료", result.category());
        assertEquals("KRW", result.currency());
    }

    @Test
    @DisplayName("필드가 누락된 JSON 은 해당 필드가 null 로 역직렬화된다")
    void missingFieldsDeserializeToNull() {
        String json = "{\"name\": \"우유\"}";

        GeminiImageResult result = objectMapper.readValue(json, GeminiImageResult.class);

        assertEquals("우유", result.name());
        assertNull(result.price());
        assertNull(result.category());
        assertNull(result.currency());
    }

    // ---------- link 경로와 같은 검증을 태운다 ----------

    @Test
    @DisplayName("LLM 이 준 음수 가격은 link 경로와 똑같이 거부된다 — 경로에 따라 검증이 갈리면 안 된다")
    void negativePriceIsRejectedLikeLinkPath() {
        GeminiImageResult result = new GeminiImageResult("우유", -100, "음료", "KRW", null);

        assertThrows(ProductSnapshotException.class, result::toImageExtraction);
    }

    @Test
    @DisplayName("공백뿐인 name 은 null 로 정규화된다 — link 경로의 fromExtracted 와 같은 처리")
    void blankNameIsNormalizedLikeLinkPath() {
        GeminiImageResult result = new GeminiImageResult("   ", 3500, "음료", "KRW", null);

        assertNull(result.toImageExtraction().snapshot().name());
    }
}
