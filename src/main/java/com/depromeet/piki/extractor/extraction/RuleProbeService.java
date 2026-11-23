package com.depromeet.piki.extractor.extraction;

import com.depromeet.piki.extractor.domain.BlockedDomains;
import com.depromeet.piki.extractor.domain.DeclaredIdentity;
import com.depromeet.piki.extractor.domain.ProductLink;
import com.depromeet.piki.extractor.domain.ProductSnapshot;
import com.depromeet.piki.extractor.extraction.http.PageFetchException;
import com.depromeet.piki.extractor.extraction.http.SiteConcurrencyLimiter;
import com.depromeet.piki.extractor.extraction.rule.MallRule;
import com.depromeet.piki.extractor.extraction.rule.RuleApplication;
import com.depromeet.piki.extractor.extraction.structured.StructuredDataExtractor;
import com.depromeet.piki.extractor.extraction.structured.StructuredExtraction;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 저장 전 규칙 하나를 실제 상품 주소에 적용해 본다(TeamPiKi/infra contracts/extraction-api.md 의 RuleProbe).
 * 배포본을 읽지 않고 LLM 도 부르지 않는다.
 */
@RequiredArgsConstructor
@Service
public class RuleProbeService {

    private final PageFetcher pageFetcher;
    private final SiteConcurrencyLimiter siteLimiter;
    private final HeadlessProductLinkExtractor headless;
    private final StructuredDataExtractor structuredDataExtractor;
    private final ObjectMapper objectMapper;

    /**
     * @param applied 규칙이 대상으로 판정하지 않았으면 null.
     * @param common 공통 규칙이 못 읽었으면 null.
     */
    public record Outcome(ProductLink finalUrl, RuleApplication applied, ProductSnapshot common) {
    }

    public Outcome probe(
        MallRule rule,
        ProductLink link,
        boolean authorized,
        DeclaredIdentity identity,
        BlockedDomains blockedDomains
    ) {
        List<Outcome> outcomes = pages(rule, link, authorized, identity, blockedDomains).stream()
            .map(page -> outcomeOf(rule, page))
            .toList();
        // 실제 추출이 채택할 문서에 가깝게 고른다 — 규칙이 온전히 읽은 문서, 없으면 대상으로 판정한 문서
        return outcomes.stream()
            .filter(outcome -> outcome.applied() != null && !outcome.applied().discarded())
            .findFirst()
            .or(() -> outcomes.stream().filter(outcome -> outcome.applied() != null).findFirst())
            .orElse(outcomes.getFirst());
    }

    private List<PageContent> pages(
        MallRule rule,
        ProductLink link,
        boolean authorized,
        DeclaredIdentity identity,
        BlockedDomains blockedDomains
    ) {
        // 실제 추출과 같은 길로 문서를 얻는다. 입력 주소로 렌더 지정을 알 수 있으면 곧바로 렌더하고, marker 규칙처럼
        // 문서를 봐야 아는 경우는 정적 문서에서 대상으로 판정됐을 때만 렌더한다
        if (rule.render() && rule.matchesHost(link)) {
            return headless.renderedPages(link, authorized, identity, blockedDomains);
        }
        PageContent fetched;
        try {
            fetched = siteLimiter.call(link, () -> pageFetcher.fetch(link, identity, blockedDomains));
        } catch (PageFetchException e) {
            if (!e.escalatable()) {
                throw e;
            }
            return headless.renderedPages(link, authorized, identity, blockedDomains);
        }
        if (rule.render() && rule.matches(fetched.finalUrl(), fetched.document())) {
            return headless.renderedPages(link, authorized, identity, blockedDomains);
        }
        return List.of(fetched);
    }

    private Outcome outcomeOf(MallRule rule, PageContent page) {
        StructuredExtraction common = structuredDataExtractor.extract(page.document(), page.link());
        return new Outcome(
            page.finalUrl(),
            rule.matches(page.finalUrl(), page.document())
                ? RuleApplication.apply(rule, page, common, objectMapper)
                : null,
            common instanceof StructuredExtraction.Extracted extracted ? extracted.snapshot() : null
        );
    }
}
