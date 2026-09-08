package com.depromeet.piki.extractor.extraction;

import com.depromeet.piki.extractor.domain.ProductLink;
import com.depromeet.piki.extractor.domain.ProductSnapshot;
import com.depromeet.piki.extractor.extraction.headless.HeadlessBlockSignal;
import com.depromeet.piki.extractor.extraction.headless.HeadlessRenderException;
import com.depromeet.piki.extractor.extraction.headless.HeadlessRenderer;
import com.depromeet.piki.extractor.extraction.headless.RenderedHop;
import com.depromeet.piki.extractor.extraction.structured.StructuredDataExtractor;
import com.depromeet.piki.extractor.extraction.structured.StructuredExtraction;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 차단 우회 헤드리스 추출 전략. 실제 브라우저를 띄우는 별도 서비스(renderer)가 거쳐 간 홉 전부를 받아,
 * 그중 어느 문서를 파이프라인에 태울지 여기서 정한다 — renderer 는 판단하지 않는다.
 *
 * <p>후보는 마지막 홉부터 거슬러 dom → body 순이다. 상품 페이지가 홈 피드·앱 유도로 튕겨 나간 경우 상품은 앞 홉에
 * 있고, 같은 홉 안에서는 하이드레이션된 dom 이 서버 원문보다 알차다(에이블리는 body 가 3KB 셸이고 상품은 dom 에만
 * 있다). 구조화 데이터가 잡히는 첫 후보를 쓰고, 없으면 마지막 홉의 dom 을 그대로 파이프라인(셸 게이트 → LLM)에
 * 넘긴다 — LLM 은 한 번만 부른다.
 */
@Slf4j
@RequiredArgsConstructor
@Component(LinkExtractionStrategy.HEADLESS)
public class HeadlessProductLinkExtractor implements LinkExtractionStrategy {

    private final HeadlessRenderer headlessRenderer;
    private final StructuredDataExtractor structuredDataExtractor;
    private final HtmlSnapshotPipeline htmlSnapshotPipeline;

    @Override
    public ProductSnapshot extract(ProductLink link, boolean authorized, String model) {
        long renderStart = System.nanoTime();
        List<RenderedHop> hops = headlessRenderer.render(link, authorized);
        long renderMs = (System.nanoTime() - renderStart) / 1_000_000;

        List<PageContent> candidates = new ArrayList<>();
        boolean blocked = false;
        for (RenderedHop hop : hops.reversed()) {
            for (String html : List.of(hop.dom(), hop.body())) {
                if (html.isBlank()) {
                    continue;
                }
                PageContent page = PageContent.of(link, html, hop.url());
                if (HeadlessBlockSignal.isBlocked(hop.status(), page.document())) {
                    blocked = true;
                    continue;
                }
                candidates.add(page);
            }
        }
        if (candidates.isEmpty()) {
            log.warn("headless hops unusable blocked={} hops={} url={}", blocked, hops.size(), link.safeLogString());
            throw blocked ? HeadlessRenderException.blocked() : HeadlessRenderException.upstream("렌더 HTML 이 없다", null);
        }

        PageContent chosen = candidates.stream()
            .filter(page -> structuredDataExtractor.extract(page) instanceof StructuredExtraction.Extracted)
            .findFirst()
            .orElse(candidates.getFirst());
        return htmlSnapshotPipeline.extract(chosen, "render=" + renderMs + "ms hops=" + hops.size(), model);
    }
}
