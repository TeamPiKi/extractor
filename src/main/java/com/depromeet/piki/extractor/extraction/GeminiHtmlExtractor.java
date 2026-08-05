package com.depromeet.piki.extractor.extraction;

import com.depromeet.piki.extractor.domain.ProductLink;
import com.depromeet.piki.extractor.domain.ProductSnapshot;
import com.depromeet.piki.extractor.extraction.gemini.GeminiClient;
import com.depromeet.piki.extractor.extraction.gemini.GeminiExtractionRequest;
import com.depromeet.piki.extractor.extraction.gemini.GeminiExtractionResult;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

/**
 * 구조화 우선 파싱이 실패했을 때의 fallback. 파이프라인이 이미 파싱해 둔 Document 를 공유받아 LLM 입력으로
 * 직렬화한다. 구조화 파서가 먼저 읽기를 끝낸 뒤 호출되므로, 이 단계에서 Document 를 직접 변형(불필요 노드
 * 제거)해도 안전하다.
 */
@RequiredArgsConstructor
@Component
public class GeminiHtmlExtractor {

    private static final int MAX_LLM_CHARS = 200_000;

    private static final Pattern COMMENT_PATTERN = Pattern.compile("<!--.*?-->", Pattern.DOTALL);

    private final GeminiClient geminiClient;

    /** @param model 호출자가 지정한 모델(없으면 null). 판정·대체는 클라이언트가 지므로 여기서는 그대로 넘긴다. */
    public ProductSnapshot extract(Document document, ProductLink link, String model) {
        String html = sanitize(document);
        GeminiExtractionRequest request = GeminiExtractionRequest.forHtmlExtraction(link.value(), html);
        GeminiExtractionResult result = geminiClient.generateContent(request, GeminiExtractionResult.class, model);
        return result.toProductSnapshot(link);
    }

    /**
     * LLM 입력에서 토큰 낭비·오판 요소(JS {@code <script>}, {@code <style>}, 주석)를 걷어내고 토큰 비용
     * 상한({@code MAX_LLM_CHARS})으로 자른다.
     *
     * <p>단 데이터를 담은 script 는 보존한다 — schema.org JSON-LD(product schema)와 일반 JSON data island
     * (Next.js 의 {@code <script id="__NEXT_DATA__" type="application/json">} 등)에 상품명·가격이 들어 있어,
     * JSON-LD/OG 파서가 놓친 사이트를 LLM 이 건져내는 fallback 의 유일한 근거다. 이걸 통째로 지우면 fallback 에
     * 가격이 빠진 HTML 이 들어가 LLM 도 손쓸 수 없었다. type 판별은 jsoup 이 파싱한 attr 기준이라 charset
     * 파라미터·공백 변형에도 정확하다.
     *
     * <p>절단을 fetch 단계가 아니라 여기서 하는 이유: JS·style 을 걷어낸 뒤라 같은 길이 안에 실제 상품 정보가
     * 훨씬 더 담긴다. 순수 함수라(인스턴스 상태 무의존) static 으로 둬 stub 없이 단위 테스트한다.
     */
    static String sanitize(Document document) {
        document.select("script").stream()
            .filter(element -> !isDataScript(element.attr("type")))
            .forEach(Element::remove);
        document.select("style").remove();
        String cleaned = COMMENT_PATTERN.matcher(document.outerHtml()).replaceAll("");
        return cleaned.length() > MAX_LLM_CHARS ? cleaned.substring(0, MAX_LLM_CHARS) : cleaned;
    }

    /**
     * LLM 입력에 남길 "데이터 script" 판정. type 이 없거나 {@code text/javascript} 인 JS 코드 script 는, 가격이
     * inline 변수({@code window.__PRELOADED_STATE__} 등)에 묻혀 있더라도 코드 덩어리라 토큰만 먹고 오판을
     * 부르므로 제거 대상이다 — 그런 거대 state 사이트는 LLM 토큰 상한에도 안 맞아, 전용 파서가 답이다.
     */
    private static boolean isDataScript(String type) {
        String normalized = type.trim();
        return normalized.regionMatches(true, 0, "application/ld+json", 0, "application/ld+json".length())
            || normalized.regionMatches(true, 0, "application/json", 0, "application/json".length());
    }
}
