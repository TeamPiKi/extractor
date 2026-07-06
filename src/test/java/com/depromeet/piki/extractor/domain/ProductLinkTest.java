package com.depromeet.piki.extractor.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// PIKI-Server: product/domain/ProductLinkTest.kt 포팅 (parse·safeLogString 케이스만).
// 미지원 플랫폼 판정(verifySupportedPlatform)은 등록 입력 경계 정책이라 PIKI-Server 에 남고,
// 이 서비스 커널 ProductLink 에는 해당 메서드가 없으므로 관련 케이스는 포팅하지 않는다.
class ProductLinkTest {

    @Test
    @DisplayName("빈 문자열 또는 공백만 있으면 거부한다")
    void rejectsBlank() {
        for (String raw : List.of("", "   ", "\t\n")) {
            ProductLinkException ex = assertThrows(
                ProductLinkException.class,
                () -> ProductLink.parse(raw),
                "'" + raw + "' 는 거부되어야 함");
            assertEquals("링크를 입력해 주세요.", ex.getMessage());
        }
    }

    @Test
    @DisplayName("https 가 아닌 scheme 은 모두 거부한다")
    void rejectsNonHttpsScheme() {
        List<String> cases = List.of(
            "http://example.com",
            "ftp://example.com",
            "file:///etc/passwd",
            "javascript:alert(1)",
            "ws://example.com/socket");
        for (String raw : cases) {
            ProductLinkException ex = assertThrows(
                ProductLinkException.class,
                () -> ProductLink.parse(raw),
                raw + " 는 거부되어야 함");
            assertEquals("https 링크만 등록할 수 있어요.", ex.getMessage());
        }
    }

    @Test
    @DisplayName("URI 파싱 자체가 실패하는 raw 는 형식 오류로 거부한다")
    void rejectsMalformedUri() {
        // 예: data: URI 의 본문에 illegal character 가 들어가면 URI.create 단계에서 IllegalArgumentException.
        ProductLinkException ex = assertThrows(
            ProductLinkException.class,
            () -> ProductLink.parse("data:text/html,<h1>x</h1>"));
        assertEquals("올바른 링크 형식이 아니에요. 다시 확인해 주세요.", ex.getMessage());
    }

    @Test
    @DisplayName("예외 메시지에 원본 raw 는 포함되지 않는다")
    void doesNotLeakRawInMessage() {
        // GlobalExceptionHandler 가 message 를 응답 detail · 로그에 그대로 박는 구조라
        // 쿼리스트링에 섞일 수 있는 토큰 · 세션이 새지 않도록 message 단계에서 원본을 제거한다.
        String rawWithSecret = "data:text/html,<token=SHOULD_NOT_LEAK>";
        ProductLinkException ex = assertThrows(
            ProductLinkException.class,
            () -> ProductLink.parse(rawWithSecret));
        assertEquals("올바른 링크 형식이 아니에요. 다시 확인해 주세요.", ex.getMessage());
    }

    @Test
    @DisplayName("scheme 없이 호스트만 있는 raw 는 거부한다")
    void rejectsSchemelessHost() {
        ProductLinkException ex = assertThrows(
            ProductLinkException.class,
            () -> ProductLink.parse("example.com/product"));
        assertEquals("https 링크만 등록할 수 있어요.", ex.getMessage());
    }

    @Test
    @DisplayName("대문자 scheme 도 case-insensitive 로 통과한다")
    void acceptsUppercaseScheme() {
        ProductLink link = ProductLink.parse("HTTPS://shop.example.com/items/42");
        assertTrue(link.value().getScheme().equalsIgnoreCase("https"));
    }

    @Test
    @DisplayName("포트 query fragment 가 포함된 URL 은 그대로 보존한 채 통과한다")
    void preservesPortQueryFragment() {
        ProductLink link = ProductLink.parse("https://shop.example.com:8443/p/42?ref=home&q=1#desc");

        assertEquals("shop.example.com", link.value().getHost());
        assertEquals(8443, link.value().getPort());
        assertEquals("/p/42", link.value().getPath());
        assertEquals("ref=home&q=1", link.value().getQuery());
        assertEquals("desc", link.value().getFragment());
    }

    @Test
    @DisplayName("앞뒤 공백을 제거한 채 parse 된다")
    void trimsSurroundingWhitespace() {
        ProductLink link = ProductLink.parse("  https://x.com/y  ");
        assertEquals("https://x.com/y", link.value().toString());
    }

    @Test
    @DisplayName("같은 raw 입력으로 parse 한 ProductLink 들은 동등하다")
    void equalForSameRaw() {
        ProductLink a = ProductLink.parse("https://x.com/y");
        ProductLink b = ProductLink.parse("https://x.com/y");
        assertEquals(a, b);
    }

    @Test
    @DisplayName("toString 은 raw URL 을 그대로 노출한다")
    void toStringExposesRawUrl() {
        String raw = "https://shop.example.com/items/42";
        ProductLink link = ProductLink.parse(raw);
        assertEquals(raw, link.toString());
    }
}
