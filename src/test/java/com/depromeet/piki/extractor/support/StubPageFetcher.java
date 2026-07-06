package com.depromeet.piki.extractor.support;

import com.depromeet.piki.extractor.domain.ProductLink;
import com.depromeet.piki.extractor.extraction.PageContent;
import com.depromeet.piki.extractor.extraction.PageFetcher;
import java.util.function.Function;

// PIKI-Server: support/StubPageFetcher.kt 포팅.
// 외부 HTTP fetch 경계를 통합 테스트에서 격리하는 stub. 매 테스트가 본문에서 build 람다를 명시 세팅한다.
// default build 는 throw — 명시 세팅을 빠뜨리면 즉시 깨져 "이전 테스트의 build 가 살아남는" 함정을 차단한다.
public class StubPageFetcher implements PageFetcher {

    public Function<ProductLink, PageContent> build = link -> {
        throw new IllegalStateException("stub.build 를 테스트 본문에서 명시 세팅해야 한다. CLAUDE.md '테스트' 절 참고.");
    };

    @Override
    public PageContent fetch(ProductLink link) {
        return build.apply(link);
    }
}
