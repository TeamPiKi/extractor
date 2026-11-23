package com.depromeet.piki.extractor.extraction.rule;

import com.depromeet.piki.contracts.extraction.v1.ExtractionRule;
import com.depromeet.piki.contracts.extraction.v1.ExtractionRuleSet;
import com.depromeet.piki.extractor.domain.ProductLink;
import com.depromeet.piki.extractor.extraction.PageContent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

/** 배포본 한 판. 적용 순서대로 정렬해 들고 있어 첫 일치가 곧 적용할 규칙이다. */
@Slf4j
public final class MallRules {

    public static final MallRules NONE = new MallRules(0, List.of());

    private final long version;
    private final List<MallRule> ordered;

    private MallRules(long version, List<MallRule> rules) {
        this.version = version;
        // 안정 정렬이라 같은 순위는 배포본에 적힌 순서가 남는다
        this.ordered = rules.stream().sorted(Comparator.comparingInt(MallRule::precedence)).toList();
    }

    public static MallRules compile(ExtractionRuleSet ruleSet) {
        List<MallRule> rules = new ArrayList<>();
        for (ExtractionRule rule : ruleSet.getRulesList()) {
            try {
                rules.add(MallRule.compile(rule));
            } catch (InvalidRuleException e) {
                log.warn("extract rule={} result=invalid version={} cause={}",
                    rule.getId(), ruleSet.getVersion(), e.getMessage());
            }
        }
        return new MallRules(ruleSet.getVersion(), rules);
    }

    public long version() {
        return version;
    }

    public int size() {
        return ordered.size();
    }

    /** 입력 주소만 보고 정적 fetch 를 건너뛸 수 있는가. 적용할 규칙은 렌더한 문서로 {@link #forPage} 가 다시 정한다. */
    public Optional<MallRule> renderFirst(ProductLink link) {
        return ordered.stream()
            .filter(rule -> rule.matchesHost(link))
            .findFirst()
            .filter(MallRule::render);
    }

    public Optional<MallRule> forPage(PageContent page) {
        return ordered.stream()
            .filter(rule -> rule.matches(page.finalUrl(), page.document()))
            .findFirst();
    }
}
