package com.depromeet.piki.extractor.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AuthorizedDomainsTest {

    @Test
    @DisplayName("값과 같은 host 와 그 서브도메인만 허락한다")
    void authorizesHostAndSubdomains() {
        AuthorizedDomains domains = new AuthorizedDomains(List.of("mall.example.com"));

        assertTrue(domains.authorizes(ProductLink.parse("https://mall.example.com/p/1")));
        assertTrue(domains.authorizes(ProductLink.parse("https://m.mall.example.com/p/1")));
        assertFalse(domains.authorizes(ProductLink.parse("https://example.com/p/1")));
        assertFalse(domains.authorizes(ProductLink.parse("https://notmall.example.com.evil.test/p/1")));
    }

    @Test
    @DisplayName("형식 밖 값은 버리고 나머지만 담는다")
    void dropsMalformedValues() {
        AuthorizedDomains domains = new AuthorizedDomains(List.of("Mall.Example.com", "ok.example.com", "a b"));

        assertEquals(List.of("ok.example.com"), domains.values());
    }
}
