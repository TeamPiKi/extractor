package com.depromeet.piki.extractor.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 요청 계약의 선택 플래그 정규화. 특히 headlessAllowed 의 기본값은 안전 기본값(fail-safe)이라 계약의 일부다 —
 * 이 필드를 모르는 구버전 호출자·필드를 빠뜨린 요청이 "허가받은 것"으로 흘러가면 허가 게이트가 배포 순서에 따라
 * 조용히 뚫린다.
 */
class LinkExtractionRequestTest {

    @Test
    @DisplayName("headlessAllowed 를 안 보내면 허가 없음(false)으로 정규화된다")
    void missingHeadlessAllowedDefaultsToDenied() {
        LinkExtractionRequest request = new LinkExtractionRequest("https://shop.example.com/p", null, null, null);

        assertEquals(Boolean.FALSE, request.headlessAllowed());
    }

    @Test
    @DisplayName("headlessAllowed=true 를 보내면 허가로 그대로 전달된다")
    void explicitHeadlessAllowedIsPreserved() {
        LinkExtractionRequest request = new LinkExtractionRequest("https://shop.example.com/p", null, true, null);

        assertEquals(Boolean.TRUE, request.headlessAllowed());
    }

    @Test
    @DisplayName("headlessFirst 를 안 보내면 false 로 정규화된다")
    void missingHeadlessFirstDefaultsToFalse() {
        LinkExtractionRequest request = new LinkExtractionRequest("https://shop.example.com/p", null, null, null);

        assertEquals(Boolean.FALSE, request.headlessFirst());
    }
}
