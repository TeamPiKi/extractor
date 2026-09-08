package com.depromeet.piki.extractor.extraction;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jsoup.nodes.Document;

/**
 * 렌더된 홉이 차단·챌린지 페이지인가. 실제 브라우저도 프록시 출구 평판이나 지문에 걸리면 챌린지 페이지를 받는다.
 * 후보를 거르는 게이트가 아니라, 아무것도 못 뽑았을 때 실패 코드를 가르는 분류기다 — 봇 방어는 어떤 status 로도
 * 위장하므로 status 하나로 recall 을 버리지 않는다.
 */
final class HeadlessBlockSignal {

    /** 490 은 네이버 캡차 커스텀 코드. */
    private static final Set<Integer> BLOCK_STATUSES = Set.of(401, 403, 405, 429, 490);
    private static final String CLOUDFLARE_HEADER = "cf-mitigated";
    private static final List<String> CHALLENGE_TITLE_MARKERS = List.of(
        "잠시만 기다", "보안 확인", "access denied", "pardon our", "are you a robot", "잠시 후 다시",
        "캡차", "captcha", "시스템오류", "just a moment", "checking your browser", "attention required"
    );

    private HeadlessBlockSignal() {
    }

    /** 문서 없이도 알 수 있는 신호 — 본문 없는 403·429 도 차단으로 센다. */
    static boolean isBlocked(int status, Map<String, String> headers) {
        return BLOCK_STATUSES.contains(status) || "challenge".equalsIgnoreCase(headers.get(CLOUDFLARE_HEADER));
    }

    static boolean isChallenge(Document document) {
        String title = document.title().toLowerCase(Locale.ROOT);
        return CHALLENGE_TITLE_MARKERS.stream().anyMatch(title::contains);
    }
}
