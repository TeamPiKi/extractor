package com.depromeet.piki.extractor.extraction;

import com.depromeet.piki.extractor.domain.ProductLink;
import com.depromeet.piki.extractor.domain.ProductSnapshot;
import org.springframework.stereotype.Component;

// 정적 HTTP fetch 기반 추출 "전략"(LinkExtractionStrategy)이다. fetch 를 1회 수행하고, 이후 파싱(구조화 우선 →
// 미달이면 같은 HTML 을 Gemini fallback, 재fetch 없음)은 헤드리스 전략과 공유하는 HtmlSnapshotPipeline 에 위임한다.
// 공개 진입점은 FallbackProductLinkExtractor(ProductLinkExtractor 구현)이고, 이 빈은 그 "plain" 전략으로 주입된다.
@Component(LinkExtractionStrategy.PLAIN)
public class DefaultProductLinkExtractor implements LinkExtractionStrategy {

    private final PageFetcher pageFetcher;
    private final HtmlSnapshotPipeline htmlSnapshotPipeline;

    public DefaultProductLinkExtractor(PageFetcher pageFetcher, HtmlSnapshotPipeline htmlSnapshotPipeline) {
        this.pageFetcher = pageFetcher;
        this.htmlSnapshotPipeline = htmlSnapshotPipeline;
    }

    @Override
    public ProductSnapshot extract(ProductLink link) {
        long fetchStart = System.nanoTime();
        PageContent page = pageFetcher.fetch(link);
        long fetchMs = (System.nanoTime() - fetchStart) / 1_000_000;

        return htmlSnapshotPipeline.extract(page, "fetch=" + fetchMs + "ms");
    }
}
