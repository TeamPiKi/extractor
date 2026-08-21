package com.depromeet.piki.extractor.extraction;

import com.depromeet.piki.extractor.domain.ProductLink;
import org.jsoup.nodes.Document;

/**
 * 수신이 끝난 한 페이지. 문자열이 아니라 파싱된 Document 를 드는 이유는 하류가 전부 Document 를 원하기
 * 때문이다 - 구조화 파서 · LLM 게이트 · sanitize · 셸 판정이 모두 그렇고, 문자열은 중간 표현일 뿐이었다.
 * Document 를 들고 다니면 수신 단계에서 스트리밍 가지치기({@link PruningHtmlParser})가 성립하고, 같은 HTML 을
 * 두 번 파싱하던 것도 사라진다.
 *
 * @param link 요청받은 원본 URL. 호출자 쪽 저장·식별의 정체성이라 redirect 와 무관하게 원본을 유지한다.
 * @param document 가지친 문서. baseUri 는 finalUrl 로 박혀 있어 상대 URL resolve 가 최종 host 기준이 된다.
 * @param finalUrl redirect 를 따라간 최종 페이지 URL. document 의 출처다.
 * @param retainedChars 가지친 뒤 남은 분량. 로그의 크기 지표이며, 셸은 이 값이 극단적으로 작다.
 */
public record PageContent(
    ProductLink link,
    Document document,
    ProductLink finalUrl,
    int retainedChars
) {

    /**
     * 이미 문자열로 들고 있는 HTML 로 조립하는 편의 팩토리(redirect 를 따라가지 않은 경우). 스트림이 아니라
     * 바운드할 대상이 없으므로 상한을 두지 않는다 - 상한은 스트림을 쥔 수신 경계가 자기 설정으로 정한다.
     */
    public static PageContent of(ProductLink link, String html) {
        return of(link, html, link);
    }

    /** redirect 를 따라간 경우 — baseUri 는 html 의 출처인 finalUrl 을 쓴다. */
    public static PageContent of(ProductLink link, String html, ProductLink finalUrl) {
        PruningHtmlParser.Pruned pruned =
            PruningHtmlParser.parse(html, finalUrl.value().toString(), PruningHtmlParser.UNBOUNDED);
        return new PageContent(link, pruned.document(), finalUrl, pruned.retainedChars());
    }
}
