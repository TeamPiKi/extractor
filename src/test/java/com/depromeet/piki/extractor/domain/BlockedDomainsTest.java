package com.depromeet.piki.extractor.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BlockedDomainsTest {

    private final BlockedDomains priceComparison = BlockedDomains.fromRequest(List.of("search.shopping.naver.com"));

    @ParameterizedTest(name = "[{0}] 은 차단 도메인이다")
    @ValueSource(strings = {
        "https://search.shopping.naver.com/catalog/1",
        "https://m.search.shopping.naver.com/catalog/1",
        "https://Search.Shopping.Naver.COM/catalog/1",
        "https://search.shopping.naver.com./catalog/1",
    })
    void blocksTheDomainAndItsSubdomains(String url) {
        assertTrue(priceComparison.blocks(ProductLink.parse(url)));
    }

    @ParameterizedTest(name = "[{0}] 은 차단 도메인이 아니다")
    @ValueSource(strings = {
        "https://shopping.naver.com/p",
        "https://notsearch.shopping.naver.com/p",
        "https://search.shopping.naver.com.evil.example/p",
        "https://naver.me/abc",
    })
    void doesNotBlockParentsOrLookalikes(String url) {
        assertFalse(priceComparison.blocks(ProductLink.parse(url)));
    }

    /** 렌더 요청에서 브라우저 실행 인자에 그대로 실리는 값이다. 쉼표 하나로 다른 이름의 해석 규칙이 끼어들 수 있다. */
    @ParameterizedTest(name = "형식 위반 [{0}] 은 버린다")
    @ValueSource(strings = {
        "x.example, MAP * 10.0.1.45", "x.example ~NOTFOUND", "*.x.example", "X.EXAMPLE", "", "x..example",
        ".x.example", "x.example.", "피키.example",
    })
    void dropsMalformedValues(String requested) {
        assertSame(BlockedDomains.NONE, BlockedDomains.fromRequest(List.of(requested)));
    }

    @Test
    @DisplayName("형식 밖 값은 요청 경로를 거치지 않아도 담을 수 없다")
    void constructorRejectsMalformedValues() {
        assertThrows(IllegalArgumentException.class, () -> new BlockedDomains(List.of("x.example, MAP * 10.0.1.45")));
    }

    @Test
    @DisplayName("253자를 넘는 값은 버린다")
    void dropsTooLongValue() {
        assertSame(BlockedDomains.NONE, BlockedDomains.fromRequest(List.of("a".repeat(254))));
    }

    @Test
    @DisplayName("형식 위반 값 하나가 나머지를 버리게 하지 않는다")
    void keepsWellFormedValuesInOrder() {
        BlockedDomains domains = BlockedDomains.fromRequest(List.of("tb.cn", "bad one", "a_b-c.example"));

        assertEquals(List.of("tb.cn", "a_b-c.example"), domains.values());
    }

    @Test
    @DisplayName("빈 목록은 아무것도 막지 않는다")
    void emptyBlocksNothing() {
        assertFalse(BlockedDomains.fromRequest(List.of()).blocks(ProductLink.parse("https://search.shopping.naver.com/p")));
    }
}
