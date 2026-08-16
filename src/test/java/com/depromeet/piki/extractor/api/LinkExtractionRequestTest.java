package com.depromeet.piki.extractor.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 요청 계약의 선택 플래그 정규화. authorized 의 기본값은 안전 기본값(fail-safe)이라 계약의 일부다 —
 * 이 필드를 모르는 구버전 호출자·필드를 빠뜨린 요청이 "허락받은 것"으로 흘러가면 우회 수단이 배포 순서에 따라
 * 조용히 열린다.
 */
class LinkExtractionRequestTest {

    @Test
    @DisplayName("authorized 를 안 보내면 허락 없음(false)으로 정규화된다")
    void missingAuthorizedDefaultsToDenied() {
        LinkExtractionRequest request = new LinkExtractionRequest("https://shop.example.com/p", null, null);

        assertEquals(Boolean.FALSE, request.authorized());
    }

    @Test
    @DisplayName("authorized=true 를 보내면 허락으로 그대로 전달된다")
    void explicitAuthorizedIsPreserved() {
        LinkExtractionRequest request = new LinkExtractionRequest("https://shop.example.com/p", true, null);

        assertEquals(Boolean.TRUE, request.authorized());
    }
}
