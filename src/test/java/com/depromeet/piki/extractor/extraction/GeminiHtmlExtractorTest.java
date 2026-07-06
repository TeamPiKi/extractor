package com.depromeet.piki.extractor.extraction;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GeminiHtmlExtractorTest {

    @Test
    @DisplayName("application/json data island 와 JSON-LD 는 LLM 입력에 보존되고 JS 코드 script 는 제거된다")
    void preservesDataScriptsRemovesCodeScripts() {
        // 가격이 Next.js __NEXT_DATA__(type=application/json)에 든 사이트가 핵심 대상.
        // 과거엔 ld+json 외 script 를 전부 지워 이 가격이 fallback(Gemini) 입력에서 빠졌다.
        String html =
            """
            <html><head>
              <script id="__NEXT_DATA__" type="application/json">{"props":{"price":84150,"name":"카키 셔츠"}}</script>
              <script type="application/ld+json">{"@type":"Product","name":"x"}</script>
              <script>window.foo = 1; console.log('noise')</script>
              <script type="text/javascript">var bar = 2;</script>
            </head><body><h1>상품</h1></body></html>
            """;

        String out = GeminiHtmlExtractor.sanitize(Jsoup.parse(html));

        assertTrue(out.contains("\"price\":84150")); // application/json data island 보존
        assertTrue(out.contains("@type")); // JSON-LD 보존 (기존 동작 유지)
        assertFalse(out.contains("console.log")); // type 없는 JS 코드 제거
        assertFalse(out.contains("var bar")); // text/javascript 제거
    }

    @Test
    @DisplayName("type 없는 inline JS state script 는 제거된다 - 거대 state 사이트는 전용 파서가 책임진다")
    void removesTypelessInlineJsState() {
        // window.__PRELOADED_STATE__ 같은 inline JS 에 가격이 있어도 코드 덩어리라 LLM 입력에서 제외한다.
        // (state 가 토큰 상한보다 큰 사이트는 Gemini 가 아니라 전용 파서로 추출한다.)
        String html =
            """
            <html><head>
              <script>window.__PRELOADED_STATE__ = {"prices":{"base":{"value":29900}}}</script>
            </head><body></body></html>
            """;

        String out = GeminiHtmlExtractor.sanitize(Jsoup.parse(html));

        assertFalse(out.contains("29900"));
    }
}
