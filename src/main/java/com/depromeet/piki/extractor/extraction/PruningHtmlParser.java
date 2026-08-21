package com.depromeet.piki.extractor.extraction;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.util.Iterator;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Comment;
import org.jsoup.nodes.DataNode;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.parser.Parser;
import org.jsoup.parser.StreamParser;

/**
 * HTML 을 흘려보내며 파싱하고, 하류가 읽지 않을 노드는 닫히는 즉시 버리는 수신구.
 *
 * <p>왜 스트리밍인가: 전체를 문자열로 받아 두면 절단 전 피크가 두 배로 뛴다(바이트 배열이 살아 있는 채 UTF-16
 * 사본이 만들어진다). 그렇다고 수신 단계에서 raw 로 자르면 {@link GeminiHtmlExtractor} 가 절단을 LLM 직전으로
 * 미뤄 둔 이득 - JS·style 을 걷어낸 뒤라야 같은 길이에 상품 정보가 훨씬 더 담긴다 - 을 버린다. 가지치기는 둘
 * 다 피한다: 걷어내는 대상이 어차피 하류가 버릴 것들이라 잃는 정보가 없고, 거대 inline JS·CSS 가 메모리에 한
 * 번에 하나씩만 스쳐 간다. 문서 끝까지 훑으므로 body 하단의 JSON-LD 도 그대로 잡힌다.
 *
 * <p>버리는 것은 {@code sanitize} 가 이미 버리는 것과 같다({@code <style>} · 주석 · 데이터 아닌
 * {@code <script>}). 단 하나 다른 점은 {@code __PRELOADED_STATE__} 를 담은 JS script 로, 구조화 파서가 그것을
 * 읽으므로 여기서는 남긴다 - 판정은 {@link DataScripts} 가 소유한다.
 */
public final class PruningHtmlParser {

    /**
     * 상한 없음. 수신 경계(fetch · render)는 각자 자기 설정값을 넘기고, 이 값은 이미 메모리에 올라와 있는
     * 문자열을 파싱하는 편의 경로가 쓴다 - 그 경로에는 바운드할 스트림이 없다.
     */
    public static final int UNBOUNDED = Integer.MAX_VALUE;

    private static final String SCRIPT = "script";
    private static final String STYLE = "style";

    private PruningHtmlParser() {
    }

    /**
     * @param maxRetainedChars 가지친 뒤에도 남는 분량의 상한(백스톱). 가지치기가 걷어내지 못하는 병리적 문서
     *     (마크업만으로 부푼 응답)를 끊는다. 정상 페이지는 닿지 않는다.
     */
    public static Pruned parse(Reader reader, String baseUri, int maxRetainedChars) throws IOException {
        try (StreamParser streamParser = new StreamParser(Parser.htmlParser())) {
            streamParser.parse(reader, baseUri);
            int retained = 0;
            // StreamParser 는 Iterable 이 아니라 Iterator 만 내준다.
            Iterator<Element> elements = streamParser.iterator();
            while (elements.hasNext()) {
                Element element = elements.next();
                if (prune(element)) {
                    element.remove();
                    continue;
                }
                dropComments(element);
                retained += weigh(element);
                if (retained > maxRetainedChars) {
                    streamParser.stop();
                    return new Pruned(streamParser.document(), retained, true);
                }
            }
            return new Pruned(streamParser.document(), retained, false);
        } catch (UncheckedIOException e) {
            // Iterator 계약상 checked 를 못 던져 감싸 온 것 - 호출부가 IO 실패를 IO 로 다루게 되돌린다.
            throw e.getCause();
        }
    }

    /** 이미 문자열로 들고 있는 HTML 용(렌더 응답 · 테스트). 스트림이 아니므로 메모리 이득은 없고 가지치기만 같다. */
    public static Pruned parse(String html, String baseUri, int maxRetainedChars) {
        try {
            return parse(new StringReader(html), baseUri, maxRetainedChars);
        } catch (IOException e) {
            // StringReader 는 IO 를 하지 않는다 - 도달 불가.
            throw new UncheckedIOException(e);
        }
    }

    /**
     * @param retainedChars 가지친 뒤 남은 분량. 로그의 크기 지표로 쓴다 - 셸은 이 값이 극단적으로 작다.
     * @param truncated 상한에 걸려 문서 끝까지 못 갔는지. 정상 경로에서는 항상 false 라, true 는 조사 신호다.
     */
    public record Pruned(Document document, int retainedChars, boolean truncated) {
    }

    private static boolean prune(Element element) {
        String tag = element.normalName();
        if (STYLE.equals(tag)) {
            return true;
        }
        if (SCRIPT.equals(tag)) {
            return !DataScripts.retainForParsing(element);
        }
        return false;
    }

    /** 주석은 Element 가 아니라 StreamParser 가 따로 내주지 않는다 - 부모가 닫힐 때 그 자식으로 걷어낸다. */
    private static void dropComments(Element element) {
        // 뒤에서부터 도는 것은 remove 가 인덱스를 당기기 때문이고, childNodes() 뷰를 순회하며 지우면
        // ConcurrentModification 이 된다.
        for (int i = element.childNodeSize() - 1; i >= 0; i--) {
            if (element.childNode(i) instanceof Comment comment) {
                comment.remove();
            }
        }
    }

    /**
     * 이 Element 가 새로 더하는 분량. 자식 Element 는 닫힐 때 각자 세어졌으므로 여기서는 자기 태그·속성과
     * 직속 텍스트만 본다 - 노드마다 정확히 한 번씩 세어져 합이 문서 크기에 비례한다.
     */
    private static int weigh(Element element) {
        int chars = element.normalName().length();
        for (Attribute attribute : element.attributes()) {
            chars += attribute.getKey().length() + attribute.getValue().length();
        }
        for (int i = 0; i < element.childNodeSize(); i++) {
            Node child = element.childNode(i);
            if (child instanceof TextNode text) {
                chars += text.getWholeText().length();
            } else if (child instanceof DataNode data) {
                chars += data.getWholeData().length();
            }
        }
        return chars;
    }
}
