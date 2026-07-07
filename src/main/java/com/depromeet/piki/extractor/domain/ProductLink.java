package com.depromeet.piki.extractor.domain;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

// 미지원 플랫폼 판정은 등록 입력 경계의 정책이라 호출자(PIKI-Server) 소관이다 —
// 이 서비스의 ProductLink 는 형식 불변식(https·URI 형식)과 안전 로깅만 책임진다.
public final class ProductLink {

    private static final Set<String> HTTP_SCHEMES = Set.of("https");

    private final URI value;

    private ProductLink(URI value) {
        this.value = value;
    }

    public static ProductLink parse(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.isBlank()) {
            throw ProductLinkException.blank();
        }
        URI uri;
        try {
            uri = URI.create(trimmed);
        } catch (IllegalArgumentException e) {
            throw ProductLinkException.invalidFormat(e);
        }
        // URI.create 는 스킴 없는 "example.com/product" 도 relative URI 로 통과시키므로 명시 검증.
        // RFC 3986 은 scheme 을 case-insensitive 로 정의하므로 비교 전에 lowercase 정규화한다.
        String scheme = uri.getScheme();
        if (scheme == null || !HTTP_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))) {
            throw ProductLinkException.unsupportedScheme();
        }
        return new ProductLink(uri);
    }

    public URI value() {
        return value;
    }

    // 로그/메트릭용. 쿼리스트링·fragment 에 토큰/세션이 섞일 수 있어 host+path 만 노출한다.
    public String safeLogString() {
        String host = value.getHost() == null ? "?" : value.getHost();
        String path = value.getRawPath() == null ? "" : value.getRawPath();
        return host + path;
    }

    @Override
    public String toString() {
        return value.toString();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ProductLink that)) {
            return false;
        }
        return value.equals(that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(value);
    }
}
