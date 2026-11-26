package com.depromeet.piki.extractor.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.depromeet.piki.extractor.domain.ProductLink;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 정책 배포본의 해석(TeamPiKi/infra contracts/extraction-api.md 8절 "정책 배포본"). */
class ExtractionPolicySnapshotTest {

    @Test
    @DisplayName("배포본의 값을 그대로 읽고 모르는 필드는 무시한다")
    void parsesSnapshot() {
        ExtractionPolicySnapshot policy = ExtractionPolicySnapshot.parse("""
            {"version": "7", "linkModel": "gemini-link", "imageModel": "gemini-image", "identity": "PiKi/2",
             "blockedDomains": ["blocked.example.com"], "authorizedDomains": ["allowed.example.com"],
             "imageBucket": "piki-dev-images", "futureField": "모르는 필드"}""");

        assertEquals(7, policy.version());
        assertEquals("gemini-link", policy.linkModel());
        assertEquals("gemini-image", policy.imageModel());
        assertEquals("PiKi/2", policy.identity().value());
        assertEquals(List.of("blocked.example.com"), policy.blockedDomains().values());
        assertTrue(policy.authorizedDomains().authorizes(ProductLink.parse("https://m.allowed.example.com/p/1")));
        assertEquals("piki-dev-images", policy.imageBucket());
    }

    @Test
    @DisplayName("빈 배포본은 전부 기본값이다")
    void emptySnapshotIsDefault() {
        ExtractionPolicySnapshot policy = ExtractionPolicySnapshot.parse("{}");

        assertEquals("", policy.linkModel());
        assertTrue(policy.identity().isDefault());
        assertTrue(policy.blockedDomains().values().isEmpty());
        assertFalse(policy.authorizedDomains().authorizes(ProductLink.parse("https://shop.example.com/p/1")));
        assertFalse(policy.hasImageBucket());
    }

    @Test
    @DisplayName("형식 밖 신원과 도메인은 버리고 나머지 값은 살린다")
    void dropsMalformedValuesOnly() {
        ExtractionPolicySnapshot policy = ExtractionPolicySnapshot.parse("""
            {"identity": "bad identity!", "blockedDomains": ["Bad Domain", "blocked.example.com"], "linkModel": "gemini-link"}""");

        assertTrue(policy.identity().isDefault());
        assertEquals(List.of("blocked.example.com"), policy.blockedDomains().values());
        assertEquals("gemini-link", policy.linkModel());
    }

    @Test
    @DisplayName("JSON 이 아닌 배포본은 해석 실패로 던진다")
    void rejectsUnparsableSnapshot() {
        assertThrows(IllegalStateException.class, () -> ExtractionPolicySnapshot.parse("not json"));
    }
}
