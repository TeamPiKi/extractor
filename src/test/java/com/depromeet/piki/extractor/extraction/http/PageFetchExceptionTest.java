package com.depromeet.piki.extractor.extraction.http;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// PIKI-Server: product/service/http/PageFetchExceptionTest.kt 포팅.
// "무조건 폴백" 계약을 팩토리 단위로 고정한다: SSRF 만 빼고 모든 fetch 실패가 escalatable(일시 오류 502/503/504 포함).
class PageFetchExceptionTest {

    private final RuntimeException cause = new RuntimeException("x");

    @Test
    @DisplayName("SSRF 를 뺀 모든 fetch 실패는 escalatable 이다")
    void allFetchFailuresExceptSsrfAreEscalatable() {
        // 봇이 어떤 status 로도 위장하므로 다 헤드리스로 태운다. 일시 오류(502/503/504·빈 body)까지 포함 — 낭비는 관측으로 조사.
        assertTrue(PageFetchException.clientError(cause).escalatable(), "4xx");
        assertTrue(PageFetchException.permanentUpstreamError(cause).escalatable(), "500/501");
        assertTrue(PageFetchException.upstreamError(cause).escalatable(), "502/503/504(일시)");
        assertTrue(PageFetchException.emptyBody().escalatable(), "빈 body");
        assertTrue(PageFetchException.tooManyRedirects().escalatable(), "redirect 루프");
        // 커널 PageFetchException 은 malformedRedirect(cause) 단일 팩토리 — "Location 없음"은 cause 없이 null 로 호출한다.
        assertTrue(PageFetchException.malformedRedirect(null).escalatable(), "비정상 redirect");
    }

    @Test
    @DisplayName("SSRF 차단만 escalatable 이 아니다")
    void onlySsrfBlockIsNotEscalatable() {
        // 내부망에 헤드리스를 겨누는 건 SSRF 취약점이라 유일한 hard 예외(recall 이 아니라 보안).
        assertFalse(PageFetchException.blockedHost().escalatable());
    }
}
