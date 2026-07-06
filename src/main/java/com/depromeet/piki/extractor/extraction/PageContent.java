package com.depromeet.piki.extractor.extraction;

import com.depromeet.piki.extractor.domain.ProductLink;

// PIKI-Server: product/service/PageContent.kt 포팅.
public record PageContent(
    // 요청받은 원본 URL. 호출자의 저장·식별(item.link) 정체성이라 redirect 와 무관하게 원본을 유지한다.
    ProductLink link,
    String html,
    // redirect 를 따라간 최종 페이지 URL. html 의 출처이므로 상대 URL resolve(Jsoup baseUri)는 이 값 기준이어야 한다.
    ProductLink finalUrl
) {

    // redirect 가 없으면 finalUrl == link.
    public static PageContent of(ProductLink link, String html) {
        return new PageContent(link, html, link);
    }
}
