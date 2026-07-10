package com.depromeet.piki.extractor.extraction;

import com.depromeet.piki.extractor.domain.ProductLink;
import com.depromeet.piki.extractor.domain.ProductSnapshot;
import com.depromeet.piki.extractor.extraction.headless.HeadlessRenderer;
import org.springframework.stereotype.Component;

// 차단 우회 헤드리스 추출 "전략". 정적 HTTP fetch 가 봇 차단에 막히는 플랫폼을, 실제 브라우저를 띄우는 별도
// 서비스(renderer 의 POST /render)로 뚫는 경로다. 렌더된 HTML 을 받아 정적 fetch 와 동일한
// 파이프라인(구조화 → LLM fallback)에 흘려넣으므로, READY 불변식(name·price·imageUrl 보장)도 같은 검증이 진다.
// 차단·빈 렌더·렌더 서비스 오류의 계약 번역(422/502)은 HeadlessRenderer 구현이 책임진다.
@Component(LinkExtractionStrategy.HEADLESS)
public class HeadlessProductLinkExtractor implements LinkExtractionStrategy {

    private final HeadlessRenderer headlessRenderer;
    private final HtmlSnapshotPipeline htmlSnapshotPipeline;

    public HeadlessProductLinkExtractor(HeadlessRenderer headlessRenderer, HtmlSnapshotPipeline htmlSnapshotPipeline) {
        this.headlessRenderer = headlessRenderer;
        this.htmlSnapshotPipeline = htmlSnapshotPipeline;
    }

    @Override
    public ProductSnapshot extract(ProductLink link) {
        long renderStart = System.nanoTime();
        PageContent page = headlessRenderer.render(link);
        long renderMs = (System.nanoTime() - renderStart) / 1_000_000;

        return htmlSnapshotPipeline.extract(page, "render=" + renderMs + "ms");
    }
}
