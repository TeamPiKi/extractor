package com.depromeet.piki.extractor.extraction;

import com.depromeet.piki.extractor.domain.ProductLink;
import com.depromeet.piki.extractor.domain.ProductSnapshot;
import com.depromeet.piki.extractor.domain.ProductSnapshotException;
import com.depromeet.piki.extractor.extraction.http.PageFetchException;
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

        try {
            return htmlSnapshotPipeline.extract(page, "fetch=" + fetchMs + "ms");
        } catch (ProductSnapshotException e) {
            // 파싱 no-data 인데 본문이 CSR 셸이면 원인은 "정적 fetch 로는 콘텐츠를 얻을 수 없음"이다 — fetch 차단과
            // 같은 축으로 재분류해(escalatable) 헤드리스가 이어받게 한다. 본문 텍스트가 충분한 페이지의 no-data 는
            // 진짜 "상품 페이지가 아님"이므로 그대로 전파한다. LLM 일시 오류(GeminiApiException)는 페이지의 문제가
            // 아니라 재분류하지 않는다(호출자 재시도 축이 흡수).
            if (EmptyShellDetector.isEmptyShell(page.html())) {
                throw PageFetchException.emptyShell(e);
            }
            throw e;
        }
    }
}
