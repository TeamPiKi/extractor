package com.depromeet.piki.extractor.extraction.headless;

import java.util.List;
import java.util.Set;
import org.jsoup.nodes.Document;

/**
 * 렌더된 홉이 차단·챌린지 페이지인가. 실제 브라우저도 프록시 출구 평판이나 지문에 걸리면 챌린지 페이지를 받으므로,
 * 그 HTML 을 "내용 없는 상품 페이지" 로 오판하지 않기 위한 분류다.
 */
public final class HeadlessBlockSignal {

    /** 490 은 네이버 캡차 커스텀 코드. */
    private static final Set<Integer> BLOCK_STATUSES = Set.of(401, 403, 405, 429, 490);
    private static final List<String> CHALLENGE_TITLE_MARKERS = List.of(
        "잠시만 기다", "보안 확인", "access denied", "pardon our",
        "are you a robot", "잠시 후 다시", "캡차", "captcha", "시스템오류"
    );

    private HeadlessBlockSignal() {
    }

    public static boolean isBlocked(int status, Document document) {
        if (BLOCK_STATUSES.contains(status)) {
            return true;
        }
        String title = document.title().toLowerCase();
        return CHALLENGE_TITLE_MARKERS.stream().anyMatch(title::contains);
    }
}
