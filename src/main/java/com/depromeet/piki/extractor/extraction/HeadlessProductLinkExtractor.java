package com.depromeet.piki.extractor.extraction;

import com.depromeet.piki.extractor.domain.ProductLink;
import com.depromeet.piki.extractor.domain.ProductSnapshot;
import com.depromeet.piki.extractor.extraction.headless.HeadlessRenderer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 차단 우회 헤드리스 추출 전략. 정적 HTTP fetch 가 봇 차단에 막히는 플랫폼을, 실제 브라우저를 띄우는 별도
 * 서비스(renderer 의 {@code POST /render})로 뚫는다. 렌더된 HTML 을 정적 fetch 와 같은
 * {@link HtmlSnapshotPipeline} 에 흘려넣으므로 READY 불변식 검증도 동일하다.
 *
 * <p>차단·빈 렌더·렌더 서비스 오류의 계약 번역은 {@code HeadlessRenderer} 구현이 책임진다.
 */
@RequiredArgsConstructor
@Component(LinkExtractionStrategy.HEADLESS)
public class HeadlessProductLinkExtractor implements LinkExtractionStrategy {

    private final HeadlessRenderer headlessRenderer;
    private final HtmlSnapshotPipeline htmlSnapshotPipeline;

    @Override
    public ProductSnapshot extract(ProductLink link, String model) {
        long renderStart = System.nanoTime();
        PageContent page = headlessRenderer.render(link);
        long renderMs = (System.nanoTime() - renderStart) / 1_000_000;

        return htmlSnapshotPipeline.extract(page, "render=" + renderMs + "ms", model);
    }
}
