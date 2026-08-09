package com.depromeet.piki.extractor.extraction;

import java.util.Objects;
import org.jsoup.nodes.Document;

/**
 * "LLM 이 볼 게 아무것도 없는가" 판정 — LLM fallback 직전의 게이트. 빈 CSR 셸을 LLM 에 넘기면 "모르겠다"
 * 대신 그럴듯한 상품을 지어내고(에이블리 mobile.* 실측: 같은 URL 15회 중 200 이 8회, 전부 서로 다른
 * 실존하지 않는 상품·죽은 이미지 URL), 지어낸 값은 형식이 유효해 응답 경계도 호출자(core) 검증도 통과한다.
 * 입력이 비었음을 아는 이 지점이 유일한 차단 기회다.
 *
 * <p>{@link EmptyShellDetector}(에스컬레이션 축)와 판정을 분리하는 이유는 오탐 비용의 비대칭이다 — 그쪽
 * 오탐은 헤드리스 1회 낭비(fail-open)로 그치지만, 이 게이트의 오탐은 확정 실패(422)로 굳는다. 그래서
 * "짧다" 수준이 아니라 "사실상 아무것도 없다" 수준에서만 발동하고, 가시 텍스트가 없어도 데이터 script
 * (JSON-LD·data island)가 있으면 LLM 이 그걸 읽을 수 있으므로({@link GeminiHtmlExtractor} sanitize 가
 * 보존한다) 통과시킨다.
 */
final class LlmInputGate {

    /**
     * 이 미만이면 "사실상 아무것도 없음". 환각 사고 케이스는 렌더 후에도 0자였고, LLM 이 유의미하게 읽을 수
     * 있는 짧은 정상 페이지(이름·가격·구매 버튼 수준)는 이 아래로 내려오지 않는다. jsoup 의 {@code text()} 는
     * script 내용을 세지 않으므로 데이터 script 유무는 별도 조건이 진다.
     */
    private static final int MIN_MEANINGFUL_TEXT_CHARS = 50;

    private LlmInputGate() {
    }

    static boolean hasNothingForLlm(Document document) {
        Objects.requireNonNull(document, "document");
        return document.body().text().length() < MIN_MEANINGFUL_TEXT_CHARS
            && !DataScripts.hasDataScript(document);
    }
}
