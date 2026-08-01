package com.depromeet.piki.extractor.extraction;

import com.depromeet.piki.extractor.domain.ProductLink;
import com.depromeet.piki.extractor.domain.ProductSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 정적 HTTP fetch 기반 추출 전략. fetch 는 1회뿐이고, 이후 파싱(구조화 우선 → 미달이면 같은 HTML 로 LLM
 * fallback, 재fetch 없음)은 헤드리스 전략과 공유하는 {@link HtmlSnapshotPipeline} 이 맡는다.
 */
@RequiredArgsConstructor
@Component(LinkExtractionStrategy.PLAIN)
public class DefaultProductLinkExtractor implements LinkExtractionStrategy {

    private final PageFetcher pageFetcher;
    private final HtmlSnapshotPipeline htmlSnapshotPipeline;

    @Override
    public ProductSnapshot extract(ProductLink link) {
        long fetchStart = System.nanoTime();
        PageContent page = pageFetcher.fetch(link);
        long fetchMs = (System.nanoTime() - fetchStart) / 1_000_000;

        return htmlSnapshotPipeline.extract(page, "fetch=" + fetchMs + "ms");
    }
}
