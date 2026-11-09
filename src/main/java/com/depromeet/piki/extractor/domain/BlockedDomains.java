package com.depromeet.piki.extractor.domain;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;

/**
 * 요청을 보내지 않을 도메인. 값의 원장은 호출자이고 형식·대조 규칙의 정본은 TeamPiKi/infra 의 contracts/extraction.proto 다.
 * 렌더 요청에 그대로 실려 브라우저 실행 인자가 되므로 형식 밖 값은 담지 못한다. 요청 값은 {@link #fromRequest} 가 걸러
 * 담는다. 계약상 무시하는 값이라 결과가 같다.
 */
@Slf4j
public record BlockedDomains(List<String> values) {

    public static final BlockedDomains NONE = new BlockedDomains(List.of());

    private static final Pattern FORMAT = Pattern.compile("[a-z0-9_-]+(\\.[a-z0-9_-]+)*");
    private static final int MAX_LENGTH = 253;

    public BlockedDomains {
        values = List.copyOf(values);
        if (!values.stream().allMatch(BlockedDomains::wellFormed)) {
            throw new IllegalArgumentException("형식 밖 차단 도메인이 있다");
        }
    }

    public static BlockedDomains fromRequest(List<String> requested) {
        List<String> wellFormed = requested.stream().filter(BlockedDomains::wellFormed).toList();
        if (wellFormed.size() != requested.size()) {
            log.warn("blocked domains rejected count={}", requested.size() - wellFormed.size());
        }
        return wellFormed.isEmpty() ? NONE : new BlockedDomains(wellFormed);
    }

    /** host 는 소문자로, 끝 점은 떼고 비교한다. 끝 점을 붙인 이름도 같은 서버로 풀린다. */
    public boolean blocks(ProductLink link) {
        String host = link.value().getHost().toLowerCase(Locale.ROOT).replaceAll("\\.+$", "");
        return values.stream().anyMatch(domain -> host.equals(domain) || host.endsWith("." + domain));
    }

    private static boolean wellFormed(String domain) {
        return domain.length() <= MAX_LENGTH && FORMAT.matcher(domain).matches();
    }
}
