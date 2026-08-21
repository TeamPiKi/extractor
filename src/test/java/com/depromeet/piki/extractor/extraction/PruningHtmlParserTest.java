package com.depromeet.piki.extractor.extraction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.jsoup.nodes.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 가지치기 규칙과 상한 검증. 무엇을 버리느냐보다 <b>무엇을 남기느냐</b>가 위험한 쪽이다 - 하류가 읽는 script 를
 * 하나라도 더 버리면 그 사이트의 추출이 컴파일도 테스트도 안 깨진 채 조용히 실패한다.
 */
class PruningHtmlParserTest {

    private static final String BASE_URI = "https://shop.example.com/p";

    @Test
    @DisplayName("JS 코드 script 는 버린다 - 거대 inline 번들이 메모리에 쌓이지 않게 하는 것이 이 파서의 목적이다")
    void dropsPlainJavaScript() {
        Document document = prune("<html><body><script>var huge = 1;</script><p>본문</p></body></html>");

        assertTrue(document.select("script").isEmpty());
        assertEquals("본문", document.text());
    }

    @Test
    @DisplayName("JSON-LD script 는 남긴다 - 구조화 파서의 1순위 입력이다")
    void retainsJsonLd() {
        Document document = prune(
            "<html><head><script type=\"application/ld+json\">{\"@type\":\"Product\"}</script></head></html>"
        );

        assertEquals("{\"@type\":\"Product\"}", document.selectFirst("script[type]").data());
    }

    @Test
    @DisplayName("가격이 든 window __PRELOADED_STATE__ JS script 는 남긴다 - LLM 입력에서는 버리지만 구조화 파서는 읽는다")
    void retainsEmbeddedStateScript() {
        // 유니클로 계열. 여기서 버리면 StructuredDataExtractor 의 embedded state 경로가 입력을 통째로 잃는다.
        Document document = prune(
            "<html><body><script>window.__PRELOADED_STATE__ = {\"prices\":{\"base\":{\"value\":29900}}};</script></body></html>"
        );

        assertNotNull(document.selectFirst("script"));
        assertTrue(document.selectFirst("script").data().contains("29900"));
    }

    @Test
    @DisplayName("type 없이 id 만 있는 __NEXT_DATA__ script 도 남긴다")
    void retainsNextDataById() {
        Document document = prune("<html><head><script id=\"__NEXT_DATA__\">{\"props\":{}}</script></head></html>");

        assertNotNull(document.selectFirst("script#__NEXT_DATA__"));
    }

    @Test
    @DisplayName("style 과 주석은 버린다 - sanitize 가 어차피 버리는 것들이라 잃는 정보가 없다")
    void dropsStyleAndComments() {
        Document document = prune(
            "<html><head><style>.a{color:red}</style></head><body><!-- 주석 --><p>본문</p></body></html>"
        );

        assertNull(document.selectFirst("style"));
        assertFalse(document.body().html().contains("주석"));
        assertEquals("본문", document.text());
    }

    @Test
    @DisplayName("보존분 상한을 넘으면 그 뒤 내용은 들어오지 않는다")
    void stopsAtRetainedCap() {
        String html = "<html><body><p>aaaaaaaaaa</p><p>bbbbbbbbbb</p><p>cccccccccc</p></body></html>";

        PruningHtmlParser.Pruned pruned = PruningHtmlParser.parse(html, BASE_URI, 12);

        assertTrue(pruned.truncated());
        assertTrue(pruned.document().text().contains("aaaaaaaaaa"), "상한 전까지는 남아야 한다");
        assertFalse(pruned.document().text().contains("cccccccccc"), "상한을 넘긴 뒤는 들어오지 않아야 한다");
    }

    @Test
    @DisplayName("상한에 닿지 않은 문서는 truncated 가 아니다 - 정상 페이지가 조사 신호를 내지 않게")
    void normalDocumentIsNotTruncated() {
        PruningHtmlParser.Pruned pruned =
            PruningHtmlParser.parse("<html><body><p>본문</p></body></html>", BASE_URI, PruningHtmlParser.UNBOUNDED);

        assertFalse(pruned.truncated());
        assertTrue(pruned.retainedChars() > 0);
    }

    @Test
    @DisplayName("거대 inline JS 가 섞여 있어도 보존분은 실제 콘텐츠 크기에 머문다 - 이 파서를 두는 이유 그 자체")
    void hugeInlineScriptDoesNotInflateRetained() {
        String huge = "var payload = \"" + "x".repeat(5_000_000) + "\";";
        String html = "<html><head><script>" + huge + "</script></head><body><p>운동화</p></body></html>";

        PruningHtmlParser.Pruned pruned = PruningHtmlParser.parse(html, BASE_URI, PruningHtmlParser.UNBOUNDED);

        assertFalse(pruned.truncated(), "상한이 아니라 가지치기로 줄어야 한다");
        assertTrue(pruned.retainedChars() < 1_000, "5MB script 가 보존분에 들어오면 안 된다 - 실제 " + pruned.retainedChars());
        assertEquals("운동화", pruned.document().text(), "정작 필요한 내용은 그대로 남아야 한다");
    }

    @Test
    @DisplayName("baseUri 가 문서에 박혀 상대 URL 이 그 기준으로 풀린다 - redirect 를 따라갔으면 최종 host 가 기준이다")
    void baseUriResolvesRelativeUrls() {
        Document document = prune("<html><body><a href=\"/detail/1\">상세</a></body></html>");

        assertEquals("https://shop.example.com/detail/1", document.selectFirst("a").absUrl("href"));
    }

    private static Document prune(String html) {
        return PruningHtmlParser.parse(html, BASE_URI, PruningHtmlParser.UNBOUNDED).document();
    }
}
