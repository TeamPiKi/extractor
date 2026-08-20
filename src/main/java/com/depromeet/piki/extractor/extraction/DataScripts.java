package com.depromeet.piki.extractor.extraction;

import com.depromeet.piki.extractor.extraction.structured.StructuredDataExtractor;
import java.util.Objects;
import org.jsoup.nodes.DataNode;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * script 를 남길지 버릴지의 판정을 모은 곳. 판정이 **두 벌**이라는 사실 자체가 여기 박혀 있어야 한다.
 *
 * <ul>
 *   <li>{@link #retainForParsing} - 수신 가지치기({@link PruningHtmlParser})가 남길 것. 구조화 파서가 읽을
 *       script 를 하나라도 빠뜨리면 그 사이트의 추출이 조용히 깨지므로 더 넓다.</li>
 *   <li>{@link #isDataScript} - LLM 입력에 남길 것. sanitize({@link GeminiHtmlExtractor})가 보존하는 것과
 *       게이트({@link LlmInputGate})가 "LLM 이 읽을 수 있다"고 보는 것이 같아야 해, 두 벌이 되면 "sanitize 는
 *       남기는데 게이트는 없다고 판정"하는 식으로 조용히 어긋난다.</li>
 * </ul>
 *
 * <p>둘이 갈리는 지점은 embedded JS state 다 - 구조화 파서는 거기서 가격을 꺼내지만, LLM 에는 코드 덩어리라
 * 토큰만 먹고 오판을 부른다.
 */
final class DataScripts {

    private DataScripts() {
    }

    /**
     * type 이 없거나 {@code text/javascript} 인 JS 코드 script 는, 가격이 inline 변수에 묻혀 있더라도 코드
     * 덩어리라 토큰만 먹고 오판을 부르므로 데이터로 치지 않는다 - 그런 거대 state 사이트는 LLM 토큰 상한에도
     * 안 맞아, 전용 파서가 답이다. 남기는 것은 schema.org JSON-LD 와 일반 JSON data island(Next.js 의
     * {@code __NEXT_DATA__} 등)뿐이다. prefix 비교라 {@code ;charset=} 파라미터 변형에도 정확하다.
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

    /**
     * 수신 단계에서 이 script 를 남겨야 하는가. 데이터 script 에 더해, 구조화 파서가 훑는 embedded state 를
     * 담은 JS script 까지 남긴다 - 여기서 버리면 {@code StructuredDataExtractor} 의 embedded state 경로가
     * 입력을 잃는다. 그 경로가 무엇을 찾는지는 그쪽이 정본이라 상수를 그쪽에서 가져다 쓴다.
     *
     * <p>{@code id} 검사는 방어다: Next.js 는 {@code type="application/json"} 을 함께 실어 앞 조건에 이미
     * 걸리지만, type 없이 id 만 있는 변형이 오면 앞 조건만으로는 버려진다.
     */
    static boolean retainForParsing(Element script) {
        Objects.requireNonNull(script, "script");
        if (isDataScript(script.attr("type"))) {
            return true;
        }
        if (StructuredDataExtractor.NEXT_DATA_ID.equals(script.id())) {
            return true;
        }
        // data() 가 아니라 DataNode 를 직접 보는 것은 사본 때문이다 - 곧 버릴 거대 script 마다 내용을 한 벌씩
        // 더 뜨면 스트리밍으로 아낀 메모리를 여기서 도로 쓴다.
        for (int i = 0; i < script.childNodeSize(); i++) {
            if (script.childNode(i) instanceof DataNode data
                && data.getWholeData().contains(StructuredDataExtractor.EMBEDDED_STATE_MARKER)) {
                return true;
            }
        }
        return false;
    }
}
