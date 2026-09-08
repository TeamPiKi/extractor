package com.depromeet.piki.extractor.extraction;

import com.depromeet.piki.extractor.domain.ProductLink;
import com.depromeet.piki.extractor.domain.ProductSnapshot;
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
 * <p>후보 순서는 모든 홉의 dom 을 마지막 홉부터, 그다음 모든 홉의 body 를 마지막 홉부터다. 상품 페이지가 홈 피드로
 * 튕겨 나간 경우 상품은 앞 홉에 있고, 하이드레이션된 dom 이 어느 홉의 서버 원문보다 알차다(에이블리는 body 가 3KB
 * 셸이고 상품은 dom 에만 있다). 파싱은 후보를 볼 때 하고 구조화 데이터가 잡히면 즉시 끝낸다. 없으면 LLM 에 넘길
 * 것이 있는 첫 후보 한 장으로 기존 파이프라인(셸 게이트 → LLM)을 탄다 — LLM 은 한 번만 부른다.
 *
 * <p>차단 신호는 후보를 거르지 않는다. 403 뒤에 온전한 상품 JSON-LD 가 실려 오기도 하므로(봇 방어의 위장 status)
 * 구조화 데이터는 그대로 쓰고, 차단으로 보이는 문서는 LLM 후보에서만 뺀다. 아무것도 못 뽑았을 때 실패 코드를
 * 차단과 장애로 가르는 데만 쓴다.
 */
@Slf4j
@RequiredArgsConstructor
@Component(LinkExtractionStrategy.HEADLESS)
public class HeadlessProductLinkExtractor implements LinkExtractionStrategy {

    private final HeadlessRenderer headlessRenderer;
    private final StructuredDataExtractor structuredDataExtractor;
    private final HtmlSnapshotPipeline htmlSnapshotPipeline;

    private record Candidate(RenderedHop hop, String html) {
    }

    private record Chosen(PageContent page, StructuredExtraction result) {
    }

    @Override
    public ProductSnapshot extract(ProductLink link, boolean authorized, String model) {
        long renderStart = System.nanoTime();
        List<RenderedHop> hops = headlessRenderer.render(link, authorized);
        long renderMs = (System.nanoTime() - renderStart) / 1_000_000;
        String timing = "render=" + renderMs + "ms hops=" + hops.size();

        boolean blocked = hops.stream().anyMatch(hop -> HeadlessBlockSignal.isBlocked(hop.status(), hop.headers()));
        Chosen first = null;      // 차단 신호 없는 첫 후보 — 전부 셸이면 이걸로 게이트가 확정 실패를 닫는다
        Chosen fallback = null;   // 그중 LLM 에 넘길 것이 있는 첫 후보
        for (Candidate candidate : candidates(hops)) {
            PageContent page = PageContent.of(link, candidate.html(), candidate.hop().url());
            StructuredExtraction result = structuredDataExtractor.extract(page);
            if (result instanceof StructuredExtraction.Extracted) {
                return htmlSnapshotPipeline.extract(page, result, timing, model);
            }
            boolean challenge = HeadlessBlockSignal.isChallenge(page.document());
            blocked |= challenge;
            if (challenge || HeadlessBlockSignal.isBlocked(candidate.hop().status(), candidate.hop().headers())) {
                continue;
            }
            if (first == null) {
                first = new Chosen(page, result);
            }
            if (fallback == null && !LlmInputGate.hasNothingForLlm(page.document())) {
                fallback = new Chosen(page, result);
            }
        }
        Chosen chosen = fallback != null ? fallback : first;
        if (chosen != null) {
            return htmlSnapshotPipeline.extract(chosen.page(), chosen.result(), timing, model);
        }
        log.warn("headless hops unusable blocked={} hops={} url={}", blocked, hops.size(), link.safeLogString());
        throw blocked ? HeadlessRenderException.blocked() : HeadlessRenderException.upstream("렌더 HTML 이 없다", null);
    }

    private static List<Candidate> candidates(List<RenderedHop> hops) {
        List<Candidate> candidates = new ArrayList<>();
        for (RenderedHop hop : hops.reversed()) {
            if (!hop.dom().isBlank()) {
                candidates.add(new Candidate(hop, hop.dom()));
            }
        }
        for (RenderedHop hop : hops.reversed()) {
            if (!hop.body().isBlank() && !hop.body().equals(hop.dom())) {
                candidates.add(new Candidate(hop, hop.body()));
            }
        }
        return candidates;
    }
}
