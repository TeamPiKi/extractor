package com.depromeet.piki.extractor.extraction.http;

import com.depromeet.piki.extractor.domain.ProductLink;
import com.depromeet.piki.extractor.domain.ProductLinkException;
import com.depromeet.piki.extractor.domain.ProductSnapshotException;
import com.depromeet.piki.extractor.extraction.PageContent;
import com.depromeet.piki.extractor.extraction.PageFetcher;
import com.depromeet.piki.extractor.extraction.PruningHtmlParser;
import com.google.common.io.ByteStreams;
import com.google.common.io.CountingInputStream;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse;
import org.springframework.web.client.RestClientResponseException;

/**
 * fetch 용 RestClient 와 host→IP 해석(dnsResolver)을 생성자로 주입받는다. 둘 다 밖에서 교체할 수 있어야
 * 네트워크 없이 redirect 루프(3xx 따라가기·cross-domain 따라가기·다운그레이드 차단·hop 상한)를 단위 테스트로
 * 검증할 수 있다.
 *
 * <p>dnsResolver 는 PageFetchHttpClientConfig 의 RestClient 와 같은 인스턴스를 공유한다 — 가드가 검증한 IP 로
 * 실제 연결도 이뤄지게(IP pin) 해 DNS rebinding/TOCTOU 를 닫는다.
 *
 * <p>본문은 통째로 받지 않고 스트림으로 흘려 {@link PruningHtmlParser} 가 가지치며 파싱한다. 그래서
 * {@code toEntity(byte[].class)} 가 아니라 {@code exchange} 를 쓴다 — 대신 4xx/5xx 를 에러로 바꿔 주던 기본
 * 동작이 없어져 status 번역을 여기서 직접 한다. 3xx 는 Location 만 읽고 본문을 아예 읽지 않는다.
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

    /**
     * 상품 페이지일 수 없는 응답 타입. 여기 걸리면 본문을 한 바이트도 읽지 않는다 — 링크가 가리키는 것이 영상·
     * 압축파일이면 지금까지는 그것을 전부 받아 HTML 로 디코딩하려 들었다.
     *
     * <p>allowlist 가 아니라 denylist 인 이유는 recall 이다 — HTML 을 {@code text/plain} 으로, 또는 Content-Type
     * 없이 주는 몰이 실재해서(무헤더 UTF-8 페이지는 charset 폴백 근거이기도 하다) "HTML 계열만 통과"로 막으면
     * 정상 상품 페이지가 함께 잘린다. 그래서 명백한 것만 막고 미상은 통과시킨다.
     */
    private static final Set<String> BINARY_TYPES = Set.of("video", "audio", "image", "font");
    private static final Set<String> BINARY_APPLICATION_SUBTYPES =
        Set.of("octet-stream", "pdf", "zip", "gzip", "x-gzip", "x-tar", "x-7z-compressed", "x-rar-compressed");

    private final RestClient restClient;
    private final RequestScopedDnsResolver dnsResolver;
    private final InternalHostGuard internalHostGuard;
    private final int maxRedirects;
    private final int maxFetchBytes;
    private final int maxRetainedChars;

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
        this.maxFetchBytes = properties.maxFetchBytes();
        this.maxRetainedChars = properties.maxRetainedChars();
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
            // hop 마다 새 host 를 다시 검증해 점프 후 SSRF 를 막는다. 람다가 잡을 수 있게 final 로 뜬다.
            ProductLink target = current;
            internalHostGuard.verify(target);
            switch (request(target)) {
                case Received.Redirect redirect -> current = redirect.target();
                // link 는 사용자가 등록한 원본을 유지하고, finalUrl 은 redirect 를 따라간 최종 페이지 —
                // 상대 URL resolve 의 baseUri 가 원본이 아닌 최종 host 기준이 되게 한다.
                case Received.Page page -> {
                    return page(link, target, page.pruned());
                }
            }
        }
        log.warn("link fetch too many redirects url={}", link.safeLogString());
        throw PageFetchException.tooManyRedirects();
    }

    private PageContent page(ProductLink link, ProductLink finalUrl, PruningHtmlParser.Pruned pruned) {
        if (pruned.truncated()) {
            // 가지치기로도 안 줄어든 문서 — 정상 페이지는 여기 닿지 않으므로 이 줄이 반복되면 그 host 를 본다.
            log.warn(
                "link fetch stopped at retained cap chars={} url={}",
                pruned.retainedChars(),
                finalUrl.safeLogString()
            );
        }
        return new PageContent(link, pruned.document(), finalUrl, pruned.retainedChars());
    }

    /**
     * uri 의 String 오버로드는 URI 템플릿으로 해석되어 {@code {q}} 같은 쿼리가 변수로 치환될 수 있으므로
     * URI 로 명시 전달한다.
     */
    private Received request(ProductLink current) {
        try {
            return restClient
                .get()
                .uri(current.value())
                .exchange((request, response) -> receive(current, response));
        } catch (ResourceAccessException e) {
            // 연결 실패와, 본문을 읽는 중의 IO 실패(연결 끊김 등)가 함께 여기로 온다.
            log.warn("link fetch upstream error url={}", current.safeLogString());
            throw PageFetchException.upstreamError(e);
        }
    }

    private Received receive(ProductLink current, ConvertibleClientHttpResponse response) throws IOException {
        HttpStatusCode status = response.getStatusCode();
        if (REDIRECT_CODES.contains(status.value())) {
            return new Received.Redirect(nextRedirect(current, response.getHeaders()));
        }
        if (status.isError()) {
            throw statusFailure(current, status, response);
        }
        MediaType contentType = response.getHeaders().getContentType();
        rejectBinary(current, contentType);
        return new Received.Page(readPruned(current, contentType, response.getBody()));
    }

    /**
     * {@code retrieve()} 가 하던 status 번역을 대신한다. 실패 응답의 본문은 cause 에 싣지 않는다 — 로그에도
     * 응답에도 쓰이지 않으면서, 봇 차단 페이지처럼 큰 본문이면 수신 바운드가 바로 여기서 샌다.
     */
    private PageFetchException statusFailure(
        ProductLink current,
        HttpStatusCode status,
        ConvertibleClientHttpResponse response
    ) throws IOException {
        log.warn("link fetch failed: status={} url={}", status, current.safeLogString());
        RestClientResponseException cause = new RestClientResponseException(
            "link fetch " + status.value(),
            status,
            response.getStatusText(),
            response.getHeaders(),
            null,
            null
        );
        if (PERMANENT_SERVER_ERRORS.contains(status.value())) {
            return PageFetchException.permanentUpstreamError(cause);
        }
        if (status.is5xxServerError()) {
            return PageFetchException.upstreamError(cause);
        }
        return PageFetchException.clientError(cause);
    }

    /**
     * 상품 페이지가 아닌 것이 확실하면 본문을 읽기 전에 끊는다. 헤드리스로 다시 시도할 여지가 없어(영상은 브라우저로
     * 열어도 영상이다) escalatable 축을 가진 PageFetchException 이 아니라 "상품 페이지가 아님" 확정 실패로 낸다.
     */
    private void rejectBinary(ProductLink current, MediaType contentType) {
        if (contentType == null) {
            return;
        }
        String type = contentType.getType().toLowerCase(Locale.ROOT);
        String subtype = contentType.getSubtype().toLowerCase(Locale.ROOT);
        boolean binary = BINARY_TYPES.contains(type)
            || ("application".equals(type) && BINARY_APPLICATION_SUBTYPES.contains(subtype));
        if (!binary) {
            return;
        }
        log.info("link fetch not a page: contentType={} url={}", contentType, current.safeLogString());
        throw ProductSnapshotException.notProductPage();
    }

    /**
     * 앞 {@value #META_SCAN_BYTES} 바이트만 mark/reset 으로 엿봐 charset 을 정한 뒤, 같은 스트림을 그 charset 의
     * Reader 로 감아 파서에 넘긴다 — 본문 전체를 문자열로 뜨지 않고도 meta charset 폴백이 성립한다.
     */
    private PruningHtmlParser.Pruned readPruned(ProductLink current, MediaType contentType, InputStream body)
        throws IOException {
        CountingInputStream counting = new CountingInputStream(body);
        BufferedInputStream buffered = new BufferedInputStream(ByteStreams.limit(counting, maxFetchBytes));
        buffered.mark(META_SCAN_BYTES);
        byte[] head = buffered.readNBytes(META_SCAN_BYTES);
        if (head.length == 0) {
            log.warn("link fetch empty body url={}", current.safeLogString());
            throw PageFetchException.emptyBody();
        }
        buffered.reset();

        Reader reader = new InputStreamReader(buffered, charsetOf(contentType, head));
        PruningHtmlParser.Pruned pruned =
            PruningHtmlParser.parse(reader, current.value().toString(), maxRetainedChars);
        if (counting.getCount() >= maxFetchBytes) {
            log.warn("link fetch stopped at byte cap bytes={} url={}", counting.getCount(), current.safeLogString());
        }
        return pruned;
    }

    /**
     * 우선순위: 응답 Content-Type 의 charset → HTML 내 meta charset → UTF-8.
     * RestClient 의 기본 String 변환(StringHttpMessageConverter)은 Content-Type 에 charset 이 없으면 ISO-8859-1 로
     * 떨어져 카카오 등 UTF-8 무헤더 페이지의 한글을 깨뜨렸다(mojibake) — 그래서 직접 정한다.
     */
    private Charset charsetOf(MediaType contentType, byte[] head) {
        Charset headerCharset = contentType == null ? null : contentType.getCharset();
        if (headerCharset != null) {
            return headerCharset;
        }
        Charset metaCharset = detectMetaCharset(head);
        if (metaCharset != null) {
            return metaCharset;
        }
        return StandardCharsets.UTF_8;
    }

    /**
     * {@code <meta charset=...>} / {@code <meta http-equiv="Content-Type" content="...charset=...">} 에서 charset 을
     * 읽는다. meta 선언은 head 앞쪽에 ASCII 로 있으므로 앞부분만 ISO-8859-1(ASCII 호환)로 훑어 토큰을 찾는다.
     * 알 수 없는 charset 이름이면 null 을 돌려 호출부가 UTF-8 로 폴백하게 한다.
     */
    private Charset detectMetaCharset(byte[] head) {
        Matcher matcher = META_CHARSET.matcher(new String(head, StandardCharsets.ISO_8859_1));
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

    /**
     * 3xx 응답의 Location 을 절대 URI 로 만들고, https 면 다음 hop 으로 따라간다.
     * cross-domain redirect 도 따라간다(무신사 OneLink·bit.ly 등 단축·딥링크가 다른 도메인의 최종 상품 페이지로
     * 보낸다). 사설망 SSRF 는 매 hop 의 {@link InternalHostGuard#verify}(IP 검증)가 막으므로, 도메인 단위 차단
     * 없이도 내부망 접근은 닫혀 있다.
     */
    private ProductLink nextRedirect(ProductLink current, HttpHeaders headers) {
        URI location = redirectLocation(current, headers);
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
    private URI redirectLocation(ProductLink current, HttpHeaders headers) {
        URI location;
        try {
            location = headers.getLocation();
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

    /** 한 hop 의 수신 결과 — 다음 hop 으로 가거나, 페이지를 얻었거나 둘 중 하나다. */
    private sealed interface Received {

        record Redirect(ProductLink target) implements Received {
        }

        record Page(PruningHtmlParser.Pruned pruned) implements Received {
        }
    }
}
