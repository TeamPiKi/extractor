package com.depromeet.piki.extractor.extraction;

import java.util.Objects;
import org.jsoup.nodes.Document;

/**
 * LLM 입력에 남길 "데이터 script" 판정의 single source. sanitize({@link GeminiHtmlExtractor})가 보존하는 것과
 * 게이트({@link LlmInputGate})가 "LLM 이 읽을 수 있다"고 보는 것이 같은 판정을 공유해야 한다 — 두 벌이 되면
 * "sanitize 는 남기는데 게이트는 없다고 판정"하는 식으로 조용히 어긋난다.
 */
final class DataScripts {

    private DataScripts() {
    }

    /**
     * type 이 없거나 {@code text/javascript} 인 JS 코드 script 는, 가격이 inline 변수
     * ({@code window.__PRELOADED_STATE__} 등)에 묻혀 있더라도 코드 덩어리라 토큰만 먹고 오판을 부르므로 데이터로
     * 치지 않는다 — 그런 거대 state 사이트는 LLM 토큰 상한에도 안 맞아, 전용 파서가 답이다. 남기는 것은
     * schema.org JSON-LD 와 일반 JSON data island(Next.js 의 {@code __NEXT_DATA__} 등)뿐이다. prefix 비교라
     * {@code ;charset=} 파라미터 변형에도 정확하다.
     */
    static boolean isDataScript(String type) {
        String normalized = type.trim();
        return normalized.regionMatches(true, 0, "application/ld+json", 0, "application/ld+json".length())
            || normalized.regionMatches(true, 0, "application/json", 0, "application/json".length());
    }

    static boolean hasDataScript(Document document) {
        Objects.requireNonNull(document, "document");
        return document.select("script").stream()
            .anyMatch(element -> isDataScript(element.attr("type")));
    }
}
