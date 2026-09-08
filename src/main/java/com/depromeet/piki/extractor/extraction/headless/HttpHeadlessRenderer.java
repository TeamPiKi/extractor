package com.depromeet.piki.extractor.extraction.headless;

import com.depromeet.piki.extractor.common.exception.ExtractionErrorCode;
import com.depromeet.piki.extractor.common.exception.ExtractionException;
import com.depromeet.piki.extractor.domain.ProductLink;
import com.depromeet.piki.extractor.extraction.HeadlessExtractionProperties;
import com.depromeet.piki.extractor.extraction.http.InternalHostGuard;
import com.depromeet.piki.extractor.extraction.http.PageFetchException;
import com.depromeet.piki.extractor.extraction.http.RequestScopedDnsResolver;
import com.github.luben.zstd.ZstdInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * renderer 의 POST /render 호출 wire 구현. 응답의 홉 목록을 {@link RenderedHop} 으로 옮기고 매 홉의 host 를
 * SSRF 가드에 통과시킨다 — 렌더 서비스가 대신 따라간 redirect 로 내부망 응답이 상품 HTML 로 흘러드는 것을 막는
 * 마지막 층이다(요청 전의 원본 URL 검증과 이중). 홉의 해석은 하지 않는다.
 *
 * <p>전송: 요청 compress=true 면 응답이 zstd raw 바이트로 온다. 해제 분기는 요청이 아니라 응답 헤더
 * ({@code X-Encoding: zstd}) 기준 — compress 를 모르는 구버전 renderer 는 plain JSON 을 주므로, 헤더 분기여야
 * renderer 와 어느 쪽이 먼저 배포되든 안전하다. 사전({@code X-Zstd-Dict})은 {@code ZstdDictionaries} 의
 * 롤아웃 규약 참조.
 */
@Slf4j
@Component
public class HttpHeadlessRenderer implements HeadlessRenderer {

    private static final String RENDER_PATH = "/render";
    private static final String ENCODING_HEADER = "X-Encoding";
    private static final String ZSTD_DICT_HEADER = "X-Zstd-Dict";
    private static final String ZSTD_ENCODING = "zstd";
    /** 해제 결과(홉 전체의 JSON)의 안전 상한. 내부망이라도 해제 폭탄·오배선을 바운드하려 둔다. */
    private static final int MAX_DECOMPRESSED_BYTES = 64 * 1024 * 1024;

    private final RestClient restClient;
    private final HeadlessExtractionProperties properties;
    private final RequestScopedDnsResolver dnsResolver;
    private final InternalHostGuard internalHostGuard;
    private final ObjectMapper objectMapper;
    private final ZstdDictionaries dictionaries;

    public HttpHeadlessRenderer(
        @Qualifier(HeadlessRenderHttpClientConfig.HEADLESS_RENDER_REST_CLIENT) RestClient restClient,
        HeadlessExtractionProperties properties,
        RequestScopedDnsResolver dnsResolver,
        ObjectMapper objectMapper,
        ZstdDictionaries dictionaries
    ) {
        this.restClient = restClient;
        this.properties = properties;
        this.dnsResolver = dnsResolver;
        this.internalHostGuard = new InternalHostGuard(dnsResolver);
        this.objectMapper = objectMapper;
        this.dictionaries = dictionaries;
    }

    @Override
    public List<RenderedHop> render(ProductLink link, boolean authorized) {
        try {
            internalHostGuard.verify(link);
            return renderVerified(link, authorized);
        } finally {
            dnsResolver.clear();
        }
    }

    private List<RenderedHop> renderVerified(ProductLink link, boolean authorized) {
        HeadlessRenderResponse response = requestRender(link, authorized);
        List<HeadlessRenderResponse.Hop> hops = response.hopsOrLegacy();
        if (hops.isEmpty()) {
            log.warn("headless render no hops error={} url={}", maskUrls(response.error()), link.safeLogString());
            throw HeadlessRenderException.upstream("렌더 홉이 없다: " + maskUrls(response.error()), null);
        }
        log.info(
            "headless render hops={} status={} proxied={} error={} url={}",
            hops.size(),
            hops.getLast().status(),
            response.proxied(),
            maskUrls(response.error()),
            link.safeLogString()
        );
        return hops.stream().map(hop -> toRendered(hop, link)).toList();
    }

    private RenderedHop toRendered(HeadlessRenderResponse.Hop hop, ProductLink link) {
        return new RenderedHop(
            resolveHopUrl(hop.url(), link),
            hop.status() == null ? 0 : hop.status(),
            hop.headers() == null ? Map.of() : hop.headers(),
            Objects.requireNonNullElse(hop.body(), ""),
            Objects.requireNonNullElse(hop.dom(), "")
        );
    }

    private HeadlessRenderResponse requestRender(ProductLink link, boolean authorized) {
        ResponseEntity<byte[]> entity;
        try {
            entity = restClient.post()
                .uri(RENDER_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new HeadlessRenderRequest(link.value().toString(), authorized, properties.compress()))
                .retrieve()
                .toEntity(byte[].class);
        } catch (RestClientResponseException e) {
            // 브라우저 실패는 200 + error 로 오므로, 렌더 서비스의 비-2xx 는 그쪽 장애·배포 중 신호다.
            throw HeadlessRenderException.upstream("render 서비스 응답 " + e.getStatusCode().value(), e);
        } catch (RestClientException e) {
            throw HeadlessRenderException.upstream("render 서비스 호출 실패", e);
        }
        return decode(entity);
    }

    private HeadlessRenderResponse decode(ResponseEntity<byte[]> entity) {
        byte[] body = entity.getBody();
        if (body == null || body.length == 0) {
            throw HeadlessRenderException.upstream("render 응답 body 가 비어 있다", null);
        }
        if (ZSTD_ENCODING.equals(entity.getHeaders().getFirst(ENCODING_HEADER))) {
            body = decompress(body, entity.getHeaders().getFirst(ZSTD_DICT_HEADER));
        }
        try {
            return objectMapper.readValue(body, HeadlessRenderResponse.class);
        } catch (JacksonException e) {
            throw HeadlessRenderException.upstream("render 응답 JSON 파싱 실패", e);
        }
    }

    private byte[] decompress(byte[] compressed, String dictId) {
        byte[] dict = resolveDict(dictId);
        try (ZstdInputStream zstd = new ZstdInputStream(new ByteArrayInputStream(compressed))) {
            if (dict != null) {
                zstd.setDict(dict);
            }
            byte[] json = zstd.readNBytes(MAX_DECOMPRESSED_BYTES + 1);
            if (json.length > MAX_DECOMPRESSED_BYTES) {
                throw HeadlessRenderException.upstream(
                    "zstd 해제 결과가 상한(" + MAX_DECOMPRESSED_BYTES + " bytes)을 넘는다", null);
            }
            log.info("headless render transfer=zstd dict=[{}] {}→{} bytes", dictId, compressed.length, json.length);
            return json;
        } catch (IOException e) {
            // 손상 바이트, 또는 사전 불일치(같은 ID 인데 내용이 다른 파일) — 여기선 못 가르므로 일시로 두고
            // 재시도에 맡긴다. 반복되면 양쪽 사전 배포 상태를 점검해야 한다.
            throw HeadlessRenderException.upstream("zstd 해제 실패 (dict=[" + dictId + "])", e);
        }
    }

    /** {@code X-Zstd-Dict} 빈값(또는 구버전이라 헤더 없음) = 사전 없이 압축됨 → plain 해제. */
    private byte[] resolveDict(String dictId) {
        if (dictId == null || dictId.isBlank()) {
            return null;
        }
        return dictionaries.find(dictId).orElseThrow(() -> HeadlessRenderException.upstream(
            "미보유 zstd 사전 [" + dictId + "] — 롤아웃 규약(사전은 extractor 먼저 배포) 위반이거나 사전ID 가 어긋났다",
            null
        ));
    }

    /**
     * 홉 URL 은 Jsoup baseUri 이자 응답 계약의 finalUrl 로 호출자에게 나간다. 형식 위반은 원본 link 로 폴백한다 —
     * baseUri 부정확은 치명이 아니고, 여기서 INVALID_URL 을 새면 렌더는 성공했는데 확정 실패로 종결되는 오판이 된다.
     * 단 SSRF 판정은 폴백하지 않고 렌더 전체를 거부한다.
     */
    private ProductLink resolveHopUrl(String url, ProductLink link) {
        if (url == null || url.isBlank()) {
            return link;
        }
        ProductLink parsed;
        try {
            parsed = ProductLink.parse(url);
        } catch (ExtractionException e) {
            return link;
        }
        try {
            internalHostGuard.verify(parsed);
        } catch (PageFetchException e) {
            if (e.code() == ExtractionErrorCode.BLOCKED_HOST) {
                throw e;
            }
            // 그 외(DNS 미해결 등)는 검증 불가일 뿐 내부망 근거가 아니다.
            log.warn("headless render hop url 검증 실패 code={} url={}", e.code(), link.safeLogString());
            return link;
        }
        return parsed;
    }

    /**
     * 렌더 서비스의 error 는 playwright 예외 원문({@code f"{type}: {e}"})이라 대상 URL 전체(쿼리스트링의 토큰 포함)가
     * 실릴 수 있다 — 로그 규약(URL 마스킹)에 맞춰 URL 패턴을 지운 채 남긴다. (package-private: 단위 테스트 대상)
     */
    static String maskUrls(String error) {
        if (error == null) {
            return null;
        }
        return error.replaceAll("https?://\\S+", "<url>");
    }
}
