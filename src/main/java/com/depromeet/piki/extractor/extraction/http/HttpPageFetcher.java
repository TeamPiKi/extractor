package com.depromeet.piki.extractor.extraction.http;

import com.depromeet.piki.extractor.domain.ProductLink;
import com.depromeet.piki.extractor.domain.ProductLinkException;
import com.depromeet.piki.extractor.extraction.PageContent;
import com.depromeet.piki.extractor.extraction.PageFetcher;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * fetch 용 RestClient 와 host→IP 해석(dnsResolver)을 생성자로 주입받는다. 둘 다 밖에서 교체할 수 있어야
 * 네트워크 없이 redirect 루프(3xx 따라가기·cross-domain 따라가기·다운그레이드 차단·hop 상한)를 단위 테스트로
 * 검증할 수 있다.
 *
 * <p>dnsResolver 는 PageFetchHttpClientConfig 의 RestClient 와 같은 인스턴스를 공유한다 — 가드가 검증한 IP 로
 * 실제 연결도 이뤄지게(IP pin) 해 DNS rebinding/TOCTOU 를 닫는다.
 */
@Slf4j
@Component
public class HttpPageFetcher implements PageFetcher {

    private static final int META_SCAN_BYTES = 4096;
    private static final Pattern META_CHARSET =
        Pattern.compile("charset\\s*=\\s*[\"']?\\s*([A-Za-z0-9_\\-]+)", Pattern.CASE_INSENSITIVE);

    /** Location 을 가진 진짜 redirect 만 따라간다 — 304 Not Modified·300 Multiple Choices·305 Use Proxy 는 제외. */
    private static final Set<Integer> REDIRECT_CODES = Set.of(301, 302, 303, 307, 308);

    /**
     * 재시도해도 결정론적으로 재실패하는 5xx 로 보는 상태 코드 — 봇 차단을 500(no body)으로 응답하는 쇼핑몰이
     * 흔해 영구 실패로 처리한다. 502/503/504 는 일시일 수 있어 제외한다.
     */
    private static final Set<Integer> PERMANENT_SERVER_ERRORS = Set.of(500, 501);

    private final RestClient restClient;
    private final RequestScopedDnsResolver dnsResolver;
    private final InternalHostGuard internalHostGuard;
    private final int maxRedirects;
    private final int maxFetchChars;

    /**
     * RestClient 빈이 둘(pageFetch·headlessRender)이라 {@code @Qualifier} 로 어느 쪽인지 명시한다 — 없으면
     * 부팅에서 NoUniqueBeanDefinition 으로 죽고, 파라미터명 우연 일치에 기대면 rename 에 조용히 깨진다.
     */
    public HttpPageFetcher(
        @Qualifier(PageFetchHttpClientConfig.PAGE_FETCH_REST_CLIENT) RestClient restClient,
        RequestScopedDnsResolver dnsResolver,
        FetchProperties properties
    ) {
        this.restClient = restClient;
        this.dnsResolver = dnsResolver;
        // 가드와 연결이 같은 resolver 를 봐야 IP pin 이 성립한다 — 같은 인스턴스로 직접 조립해 그 계약을 코드로 박는다.
        this.internalHostGuard = new InternalHostGuard(dnsResolver);
        this.maxRedirects = properties.maxRedirects();
        this.maxFetchChars = properties.maxFetchChars();
    }

    @Override
    public PageContent fetch(ProductLink link) {
        try {
            return fetchFollowingRedirects(link);
        } finally {
            dnsResolver.clear();
        }
    }

    private PageContent fetchFollowingRedirects(ProductLink link) {
        ProductLink current = link;
        for (int hop = 0; hop <= maxRedirects; hop++) {
            // redirect 를 따라갈 때도 매 hop 의 새 host 에 대해 다시 검증해 점프 후 SSRF 를 막는다.
            internalHostGuard.verify(current);
            ResponseEntity<byte[]> response = request(current);
            if (REDIRECT_CODES.contains(response.getStatusCode().value())) {
                current = nextRedirect(current, response);
                continue;
            }
            byte[] bytes = response.getBody();
            if (bytes == null) {
                log.warn("link fetch empty body url={}", current.safeLogString());
                throw PageFetchException.emptyBody();
            }
            String html = decodeHtml(bytes, headerCharset(response));
            String truncated = html.length() > maxFetchChars ? html.substring(0, maxFetchChars) : html;
            // link 는 사용자가 등록한 원본을 유지하고, finalUrl 은 redirect 를 따라간 최종 페이지(current) —
            // 상대 URL resolve 의 baseUri 가 원본이 아닌 최종 host 기준이 되게 한다.
            return new PageContent(link, truncated, current);
        }
        log.warn("link fetch too many redirects url={}", link.safeLogString());
        throw PageFetchException.tooManyRedirects();
    }

    /**
     * uri 의 String 오버로드는 URI 템플릿으로 해석되어 {@code {q}} 같은 쿼리가 변수로 치환될 수 있으므로
     * URI 로 명시 전달한다. toEntity 로 3xx status·Location 에 접근한다(기본 onStatus 는 4xx/5xx 만 에러로
     * 던지므로 3xx 는 정상 수신).
     */
    private ResponseEntity<byte[]> request(ProductLink current) {
        try {
            return restClient
                .get()
                .uri(current.value())
                .retrieve()
                .toEntity(byte[].class);
        } catch (RestClientResponseException e) {
            log.warn("link fetch failed: status={} url={}", e.getStatusCode(), current.safeLogString());
            int status = e.getStatusCode().value();
            if (PERMANENT_SERVER_ERRORS.contains(status)) {
                throw PageFetchException.permanentUpstreamError(e);
            }
            if (e.getStatusCode().is5xxServerError()) {
                throw PageFetchException.upstreamError(e);
            }
            throw PageFetchException.clientError(e);
        } catch (ResourceAccessException e) {
            log.warn("link fetch upstream error url={}", current.safeLogString());
            throw PageFetchException.upstreamError(e);
        }
    }

    /**
     * 3xx 응답의 Location 을 절대 URI 로 만들고, https 면 다음 hop 으로 따라간다.
     * cross-domain redirect 도 따라간다(무신사 OneLink·bit.ly 등 단축·딥링크가 다른 도메인의 최종 상품 페이지로
     * 보낸다). 사설망 SSRF 는 매 hop 의 {@link InternalHostGuard#verify}(IP 검증)가 막으므로, 도메인 단위 차단
     * 없이도 내부망 접근은 닫혀 있다.
     */
    private ProductLink nextRedirect(ProductLink current, ResponseEntity<byte[]> response) {
        URI location = redirectLocation(current, response);
        URI target = current.value().resolve(location);
        // https 강제(다운그레이드 차단)와 형식 검증을 ProductLink.parse 가 겸한다 — 둘 중 하나라도 어긋난
        // redirect 는 따라가지 않는다.
        try {
            return ProductLink.parse(target.toString());
        } catch (ProductLinkException e) {
            log.warn("link fetch redirect rejected (non-https or malformed) url={}", current.safeLogString());
            throw PageFetchException.blockedHost();
        }
    }

    /**
     * 외부 서버가 깨진 Location(잘못된 이스케이프 등)을 주거나 3xx 인데 Location 을 아예 안 주면, 우리 버그가
     * 아니라 대상 서버의 비정상 redirect 응답이다 — 재시도해도 결정론적으로 재실패하므로 일시(upstreamError)가
     * 아니라 malformedRedirect 로 귀결시킨다.
     */
    private URI redirectLocation(ProductLink current, ResponseEntity<byte[]> response) {
        URI location;
        try {
            location = response.getHeaders().getLocation();
        } catch (IllegalArgumentException e) {
            log.warn("link fetch malformed Location url={}", current.safeLogString());
            throw PageFetchException.malformedRedirect(e);
        }
        if (location == null) {
            log.warn("link fetch redirect without Location url={}", current.safeLogString());
            throw PageFetchException.malformedRedirect(null);
        }
        return location;
    }

    private Charset headerCharset(ResponseEntity<byte[]> response) {
        MediaType contentType = response.getHeaders().getContentType();
        if (contentType == null) {
            return null;
        }
        return contentType.getCharset();
    }

    /**
     * 우선순위: 응답 Content-Type 의 charset → HTML 내 meta charset → UTF-8.
     * RestClient 의 String 변환(StringHttpMessageConverter)은 Content-Type 에 charset 이 없으면 ISO-8859-1 로
     * 떨어져 카카오 등 UTF-8 무헤더 페이지의 한글을 깨뜨렸다(mojibake) — 그래서 바이트로 받아 직접 정한다.
     */
    private String decodeHtml(byte[] bytes, Charset headerCharset) {
        Charset charset = headerCharset;
        if (charset == null) {
            charset = detectMetaCharset(bytes);
        }
        if (charset == null) {
            charset = StandardCharsets.UTF_8;
        }
        return new String(bytes, charset);
    }

    /**
     * {@code <meta charset=...>} / {@code <meta http-equiv="Content-Type" content="...charset=...">} 에서 charset 을
     * 읽는다. meta 선언은 head 앞쪽에 ASCII 로 있으므로 앞부분만 ISO-8859-1(ASCII 호환)로 훑어 토큰을 찾는다.
     * 알 수 없는 charset 이름이면 null 을 돌려 호출부가 UTF-8 로 폴백하게 한다.
     */
    private Charset detectMetaCharset(byte[] bytes) {
        int scanLength = Math.min(bytes.length, META_SCAN_BYTES);
        String head = new String(bytes, 0, scanLength, StandardCharsets.ISO_8859_1);
        Matcher matcher = META_CHARSET.matcher(head);
        if (!matcher.find()) {
            return null;
        }
        String name = matcher.group(1).trim();
        try {
            return Charset.forName(name);
        } catch (IllegalArgumentException e) {
            // IllegalCharsetNameException·UnsupportedCharsetException 모두 IllegalArgumentException 하위라 함께 잡힌다.
            return null;
        }
    }
}
