package com.depromeet.piki.extractor.extraction.rule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.depromeet.piki.extractor.domain.ProductLink;
import com.depromeet.piki.extractor.extraction.PageContent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MallRulesTest {

    private static final String MARKED_HTML =
        "<html><head><meta name=\"generator\" content=\"imweb\"></head><body><p>본문</p></body></html>";

    private static PageContent page(String url, String html) {
        return PageContent.of(ProductLink.parse(url), html);
    }

    @Test
    @DisplayName("host 규칙이 여럿 맞으면 host 가 가장 긴 것을, 같은 host 면 먼저 적힌 것을 고른다")
    void prefersLongestHostThenFirstListed() {
        MallRules rules = RuleFixtures.rules(
            "{\"id\": 1, \"host\": \"example.com\"}",
            "{\"id\": 2, \"host\": \"shop.example.com\"}",
            "{\"id\": 3, \"host\": \"shop.example.com\"}"
        );

        assertEquals(2, rules.forPage(page("https://shop.example.com/p/1", "<html></html>")).orElseThrow().id());
        assertEquals(1, rules.forPage(page("https://www.example.com/p/1", "<html></html>")).orElseThrow().id());
    }

    @Test
    @DisplayName("host 규칙과 marker 규칙이 함께 맞으면 배포본의 순서와 무관하게 host 규칙을 고른다")
    void hostRuleBeatsMarkerRule() {
        MallRules rules = RuleFixtures.rules(
            "{\"id\": 1, \"marker\": \"meta[name='generator']\"}",
            "{\"id\": 2, \"host\": \"example.com\"}"
        );

        assertEquals(2, rules.forPage(page("https://example.com/p/1", MARKED_HTML)).orElseThrow().id());
        assertEquals(1, rules.forPage(page("https://other.com/p/1", MARKED_HTML)).orElseThrow().id());
        assertTrue(rules.forPage(page("https://other.com/p/1", "<html></html>")).isEmpty());
    }

    @Test
    @DisplayName("해석하지 못하는 규칙 하나는 그 규칙만 버리고 나머지는 적용한다")
    void dropsOnlyTheInvalidRule() {
        MallRules rules = RuleFixtures.rules(
            "{\"id\": 1, \"host\": \"example.com\", \"name\": {}}",
            "{\"id\": 2, \"host\": \"example.com\"}"
        );

        assertEquals(1, rules.size());
        assertEquals(2, rules.forPage(page("https://example.com/p/1", "<html></html>")).orElseThrow().id());
    }

    @Test
    @DisplayName("입력 주소의 host 에 적용될 규칙이 렌더 지정일 때만 정적 fetch 를 건너뛴다")
    void rendersFirstOnlyWhenTheApplicableHostRuleSaysSo() {
        MallRules rules = RuleFixtures.rules(
            "{\"id\": 1, \"host\": \"example.com\", \"fetch\": \"RENDER\"}",
            "{\"id\": 2, \"host\": \"static.example.com\"}",
            "{\"id\": 3, \"marker\": \"meta\", \"fetch\": \"RENDER\"}"
        );

        assertEquals(1, rules.renderFirst(ProductLink.parse("https://www.example.com/p/1")).orElseThrow().id());
        assertTrue(rules.renderFirst(ProductLink.parse("https://static.example.com/p/1")).isEmpty());
        assertTrue(rules.renderFirst(ProductLink.parse("https://other.com/p/1")).isEmpty());
    }
}
