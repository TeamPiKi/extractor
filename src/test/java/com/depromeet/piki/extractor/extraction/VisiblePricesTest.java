package com.depromeet.piki.extractor.extraction;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class VisiblePricesTest {

    @Test
    @DisplayName("본문의 천 단위 구분 금액과 구분 없는 금액을 모두 같은 금액으로 본다")
    void matchesFormattedAndPlainAmounts() {
        assertTrue(VisiblePrices.contains(Jsoup.parse("<body>판매가 9,500원</body>"), 9_500));
        assertTrue(VisiblePrices.contains(Jsoup.parse("<body>9500 KRW</body>"), 9_500));
    }

    @Test
    @DisplayName("다른 금액의 일부로 들어 있는 숫자열은 같은 금액으로 보지 않는다")
    void doesNotMatchInsideAnotherAmount() {
        assertFalse(VisiblePrices.contains(Jsoup.parse("<body>1,295,000원</body>"), 9_500));
        assertFalse(VisiblePrices.contains(Jsoup.parse("<body>19500원</body>"), 9_500));
    }

    @Test
    @DisplayName("script 안에만 있는 금액은 가시 텍스트가 아니다")
    void ignoresScriptContent() {
        String html = "<html><head><script>window.__STATE__={\"price\":9500}</script></head><body>상품명</body></html>";

        assertFalse(VisiblePrices.contains(Jsoup.parse(html), 9_500));
    }
}
