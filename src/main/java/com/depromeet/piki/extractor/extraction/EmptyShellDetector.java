package com.depromeet.piki.extractor.extraction;

import java.util.Objects;
import org.jsoup.nodes.Document;

/**
 * "2xx 인데 데이터 없는 CSR 셸" 판정. 정적 fetch 가 성공해도 본문이 JS 부트스트랩뿐인 SPA 셸이면 파싱은
 * 구조적으로 항상 실패한다(카카오 톡딜 store.kakao.com 5.8KB 셸·clink 브리지 실측). 이 경우 fetch 차단(403 등)과
 * 마찬가지로 실제 브라우저(헤드리스)가 답이므로, 파싱 no-data 실패를 escalatable 로 재분류하는 근거가 된다.
 *
 * <p>가시 텍스트 길이 하나로 판정한다 — 셸은 script/meta 가 전부라 body 텍스트가 수십 자("본문 바로가기" 수준)에
 * 그치고, 상품이 아닌 진짜 콘텐츠 페이지(블로그·기사 등)는 텍스트가 길어 셸로 오판되지 않는다. jsoup 의
 * {@code text()} 는 script/style 내용을 텍스트로 세지 않아 이 구분에 그대로 맞는다. 짧은 정상 페이지가 셸로
 * 오탐되는 쪽은 헤드리스 1회 낭비로 그치므로(escalation 메트릭으로 관측) fail-open 이 싸다.
 */
final class EmptyShellDetector {

    /**
     * 이 미만이면 셸. 실측 셸(카카오 store·clink 브리지)은 수십 자, 콘텐츠 페이지는 수백 자 이상이라 간극이 넓다.
     */
    private static final int MIN_VISIBLE_TEXT_CHARS = 300;

    private EmptyShellDetector() {
    }

    static boolean isEmptyShell(Document document) {
        Objects.requireNonNull(document, "document");
        return document.body().text().length() < MIN_VISIBLE_TEXT_CHARS;
    }
}
