package com.depromeet.piki.extractor.extraction;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LlmInputGateTest {

    @Test
    @DisplayName("가시 텍스트도 데이터 script 도 없는 CSR 셸은 LLM 이 볼 게 없다고 판정한다")
    void emptyShellHasNothingForLlm() {
        // 에이블리 mobile.* 실측 축약형 — 렌더 후에도 스타일·JS 부트스트랩뿐, 가시 텍스트 0자.
        String shell = "<html><head><style id=\"react-native-stylesheet\"></style>"
            + "<meta name=\"__META_TAGS__\">"
            + "<script src=\"/assets/index.js\"></script>"
            + "<script>window.__BOOT__ = { app: 'ably' };</script>"
            + "</head><body><div id=\"root\"></div></body></html>";

        assertTrue(LlmInputGate.hasNothingForLlm(Jsoup.parse(shell)));
    }

    @Test
    @DisplayName("가시 텍스트가 없어도 JSON-LD 가 있으면 LLM 이 읽을 수 있다 — 게이트를 통과시킨다")
    void ldJsonScriptPassesGate() {
        String html = "<html><head><script type=\"application/ld+json\">"
            + "{\"@type\":\"Product\",\"name\":\"상품\",\"offers\":{\"price\":\"1000\"}}"
            + "</script></head><body></body></html>";

        assertFalse(LlmInputGate.hasNothingForLlm(Jsoup.parse(html)));
    }

    @Test
    @DisplayName("가시 텍스트가 없어도 JSON data island 가 있으면 게이트를 통과시킨다 - hydration 전 SPA 구제")
    void jsonDataIslandPassesGate() {
        String html = "<html><head><script id=\"__NEXT_DATA__\" type=\"application/json\">"
            + "{\"props\":{\"product\":{\"name\":\"상품\",\"price\":1000}}}"
            + "</script></head><body><div id=\"__next\"></div></body></html>";

        assertFalse(LlmInputGate.hasNothingForLlm(Jsoup.parse(html)));
    }

    @Test
    @DisplayName("type 없는 JS 코드 script 는 데이터로 치지 않는다 — sanitize 가 지우는 것과 같은 판정")
    void typelessJsScriptDoesNotPassGate() {
        String html = "<html><head>"
            + "<script>window.__PRELOADED_STATE__ = { price: 9900 };</script>"
            + "</head><body></body></html>";

        assertTrue(LlmInputGate.hasNothingForLlm(Jsoup.parse(html)));
    }

    @Test
    @DisplayName("공백뿐인 body 는 발동하고 가시 텍스트 한 글자라도 있으면 통과한다 (경계 - 길이 임계 없음)")
    void thresholdBoundary() {
        // jsoup text() 는 공백을 정규화하므로 공백만 있는 body 는 "전혀 없음"이다.
        assertTrue(LlmInputGate.hasNothingForLlm(Jsoup.parse("<body>   </body>")));
        assertFalse(LlmInputGate.hasNothingForLlm(Jsoup.parse("<body>a</body>")));
    }

    @Test
    @DisplayName("가시 텍스트만으로 이뤄진 미니멀 상품 페이지는 통과한다 - 오탐이 확정 실패로 굳는 축이라 사실 판정만")
    void minimalVisibleTextPagePassesGate() {
        // 데이터가 script 가 아니라 가시 텍스트에 있는 미니멀 페이지 — EmptyShellDetector(300자) 기준으로는
        // 셸이지만, LLM 은 이 텍스트를 읽을 수 있으므로 게이트가 막으면 안 된다.
        String minimal = "<html><body><h1>알레 여리핏 골지 살안타 라운드 가디건</h1>"
            + "<p>15,410원</p><button>구매하기</button><footer>교환·환불 안내</footer></body></html>";

        assertFalse(LlmInputGate.hasNothingForLlm(Jsoup.parse(minimal)));
    }
}
