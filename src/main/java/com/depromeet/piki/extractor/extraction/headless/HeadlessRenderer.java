package com.depromeet.piki.extractor.extraction.headless;

import com.depromeet.piki.extractor.domain.ProductLink;
import com.depromeet.piki.extractor.extraction.PageContent;

// 헤드리스 브라우저 렌더(renderer 의 POST /render) 외부 경계 — 테스트 stub 지점 (PageFetcher 와 같은 역할).
// 렌더된 HTML 이 있는 페이지만 PageContent 로 반환한다. 차단(BLOCK)·빈 렌더·서비스 오류는 HeadlessRenderException
// (일시 502)으로, SSRF 차단 host 는 PageFetchException.blockedHost(확정 422)로 번역해 던진다 —
// 소비자(HeadlessProductLinkExtractor)는 verdict 를 모른다.
public interface HeadlessRenderer {

    PageContent render(ProductLink link);
}
