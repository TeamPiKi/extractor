package com.depromeet.piki.extractor.domain;

import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * 요청을 보내지 않을 도메인. 값의 원장은 호출자이고 형식·대조 규칙의 정본은 TeamPiKi/infra 의 contracts/extraction.proto 다.
 * 렌더 요청에 그대로 실려 브라우저 실행 인자가 되므로 형식 밖 값은 담지 못한다. 요청 값은 {@link #fromRequest} 가 걸러
 * 담는다. 계약상 무시하는 값이라 결과가 같다.
 */
@Slf4j
public record BlockedDomains(List<String> values) {

    public static final BlockedDomains NONE = new BlockedDomains(List.of());

    public BlockedDomains {
        values = List.copyOf(values);
        if (!values.stream().allMatch(DomainNames::wellFormed)) {
            throw new IllegalArgumentException("형식 밖 차단 도메인이 있다");
        }
    }

    public static BlockedDomains fromRequest(List<String> requested) {
        List<String> wellFormed = requested.stream().filter(DomainNames::wellFormed).toList();
        if (wellFormed.size() != requested.size()) {
            log.warn("blocked domains rejected count={}", requested.size() - wellFormed.size());
        }
        return wellFormed.isEmpty() ? NONE : new BlockedDomains(wellFormed);
    }

    public boolean blocks(ProductLink link) {
        return DomainNames.covers(values, link);
    }
}
