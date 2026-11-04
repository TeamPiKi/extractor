package com.depromeet.piki.extractor.domain;

import java.util.regex.Pattern;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 대상 몰에 밝히는 신원. 값의 원장은 호출자이고 형식의 정본은 TeamPiKi/infra 의 contracts/extraction.proto 다.
 * 헤더에 그대로 실리는 값이라 호출자를 믿지 않고 다시 본다. 어긋난 값은 기본 신원으로 돌려 신원이 빠진 채 나가는
 * 경로를 두지 않는다.
 */
@Slf4j
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class DeclaredIdentity {

    /** 값이 비어 있으면 각 경로의 기본 신원을 쓴다. 렌더 요청에도 빈 값으로 나가 렌더 서비스의 기본값을 탄다. */
    public static final DeclaredIdentity DEFAULT = new DeclaredIdentity("");

    private static final Pattern FORMAT = Pattern.compile("[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)?");
    private static final int MAX_LENGTH = 40;
    private static final Pattern CONTROL_CHARS = Pattern.compile("\\p{Cntrl}");

    private final String value;

    public static DeclaredIdentity fromRequest(String requested) {
        if (requested == null || requested.isEmpty()) {
            return DEFAULT;
        }
        if (requested.length() > MAX_LENGTH || !FORMAT.matcher(requested).matches()) {
            log.warn("identity rejected, using default: {}", CONTROL_CHARS.matcher(requested).replaceAll("?"));
            return DEFAULT;
        }
        return new DeclaredIdentity(requested);
    }

    public boolean isDefault() {
        return value.isEmpty();
    }

    public String value() {
        return value;
    }

    @Override
    public String toString() {
        return isDefault() ? "default" : value;
    }
}
