package com.depromeet.piki.extractor.domain;

import java.util.List;

/**
 * 플랫폼의 명시적 허락을 받은 도메인. 값의 원장은 호출자이고, 형식 밖 값은 계약상 무시한다 — 어느 host 와도 맞지 않는
 * 값이라 결과가 같다.
 */
public record AuthorizedDomains(List<String> values) {

    public static final AuthorizedDomains NONE = new AuthorizedDomains(List.of());

    public AuthorizedDomains {
        values = values.stream().filter(DomainNames::wellFormed).toList();
    }

    public boolean authorizes(ProductLink link) {
        return DomainNames.covers(values, link);
    }
}
