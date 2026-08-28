package com.depromeet.piki.extractor.extraction;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.jsoup.nodes.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EmptyShellDetectorTest {

    @Test
    @DisplayName("script 와 meta 뿐인 SPA 셸은 빈 셸로 판정한다")
    void spaShellIsEmptyShell() {
        // 카카오 store.kakao.com 셸 실측 축약형 — og 메타는 서비스 고정값, body 는 접근성 링크와 빈 마운트 지점뿐.
        String shell = "<html><head>"
            + "<meta property=\"og:title\" content=\"톡딜\">"
            + "<meta property=\"og:site_name\" content=\"톡딜\">"
            + "<script src=\"https://cdn.example.com/chunk-1.js\"></script>"
            + "<script>window.__BOOT__ = { app: 'talkstore', flags: [1, 2, 3] };</script>"
            + "</head><body><a href=\"#content\">본문 바로가기</a><a href=\"#menu\">메뉴 바로가기</a>"
            + "<div id=\"app\"></div></body></html>";

        assertTrue(EmptyShellDetector.isEmptyShell(document(shell)));
    }

    @Test
    @DisplayName("JS 리다이렉트만 있는 브리지 페이지는 빈 셸로 판정한다")
    void bridgePageIsEmptyShell() {
        // clink.kakao.com 브리지 실측 축약형 — 콘텐츠 없이 location.href 한 줄로 상품 페이지에 연결된다.
        String bridge = "<html><head><title>카카오톡 추천리워드</title></head><body>"
            + "<script>location.href = \"https://store.kakao.com/kgcmall/products/445653929\";</script>"
            + "</body></html>";

        assertTrue(EmptyShellDetector.isEmptyShell(document(bridge)));
    }

    @Test
    @DisplayName("가시 텍스트 299자는 빈 셸이고 300자는 아니다 (경계)")
    void thresholdBoundary() {
        assertTrue(EmptyShellDetector.isEmptyShell(document("<body>" + "a".repeat(299) + "</body>")));
        assertFalse(EmptyShellDetector.isEmptyShell(document("<body>" + "a".repeat(300) + "</body>")));
    }

    @Test
    @DisplayName("본문 텍스트가 충분한 페이지는 빈 셸이 아니다")
    void contentRichPageIsNotEmptyShell() {
        // 상품이 아닌 콘텐츠 페이지(블로그·기사 등) — no-data 라도 셸이 아니므로 재분류 대상이 아니다.
        String article = "<html><body><article>"
            + "오늘은 카카오 쇼핑의 추천 상품을 소개하는 긴 글입니다. ".repeat(20)
            + "</article></body></html>";

        assertFalse(EmptyShellDetector.isEmptyShell(document(article)));
    }

    /** 운영에서 판정에 닿는 문서는 항상 가지친 것이므로 같은 경로로 만든다. */
    private static Document document(String html) {
        return PruningHtmlParser.parse(html, "https://shop.example.com/p").document();
    }
}
