package com.depromeet.piki.extractor.domain;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** 도메인 목록의 형식과 대조 규칙. 정본은 TeamPiKi/infra 의 contracts/extraction.proto 의 blocked_domains 다. */
final class DomainNames {

    private static final Pattern FORMAT = Pattern.compile("[a-z0-9_-]+(\\.[a-z0-9_-]+)*");
    private static final int MAX_LENGTH = 253;

    private DomainNames() {
    }

    static boolean wellFormed(String domain) {
        return domain.length() <= MAX_LENGTH && FORMAT.matcher(domain).matches();
    }

    /** host 는 소문자로, 끝 점은 떼고 비교한다. 끝 점을 붙인 이름도 같은 서버로 풀린다. */
    static boolean covers(List<String> domains, ProductLink link) {
        String host = link.value().getHost().toLowerCase(Locale.ROOT).replaceAll("\\.+$", "");
        return domains.stream().anyMatch(domain -> host.equals(domain) || host.endsWith("." + domain));
    }
}
