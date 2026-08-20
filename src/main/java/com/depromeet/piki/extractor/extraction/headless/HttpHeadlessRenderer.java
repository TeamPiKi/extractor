package com.depromeet.piki.extractor.extraction.headless;

import com.depromeet.piki.extractor.common.exception.ExtractionErrorCode;
import com.depromeet.piki.extractor.common.exception.ExtractionException;
import com.depromeet.piki.extractor.domain.ProductLink;
import com.depromeet.piki.extractor.extraction.HeadlessExtractionProperties;
import com.depromeet.piki.extractor.extraction.PageContent;
import com.depromeet.piki.extractor.extraction.PruningHtmlParser;
import com.depromeet.piki.extractor.extraction.http.InternalHostGuard;
import com.depromeet.piki.extractor.extraction.http.PageFetchException;
import com.depromeet.piki.extractor.extraction.http.RequestScopedDnsResolver;
import com.github.luben.zstd.ZstdInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
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
 * renderer 의 POST /render 호출 + verdict 를 계약으로 번역하는 wire 구현.
 * 렌더 서비스는 실제 브라우저(patchright)로 페이지를 열어 최선의 HTML 을 돌려준다 — 파싱은 그쪽이 하지 않으므로
 * 렌더된 HTML 을 PageContent 로 되돌려 기존 파이프라인(구조화 → LLM)에 흘려넣는다.
 *
 * <p>verdict 번역 규칙 — recall 최대화:
 * <ul>
 *   <li>BLOCK → {@link HeadlessRenderException#blocked()}. 일시 신호가 섞여 영구/일시를 가를 수 없어
 *       fail-safe 로 일시에 두는 근거는 그 팩토리에 있다.</li>
 *   <li>그 외 + html 있음 → 진행. verdict 는 렌더 서비스가 자기 정규식 파서로 title·price 를 찾았는지일 뿐이라,
 *       EMPTY 여도 그 DOM 에서 우리 파이프라인(구조화·LLM)이 뽑아낼 수 있다 — verdict 로 HTML 을 버리면
 *       그 recall 을 잃는다.</li>
 *   <li>그 외 + html 없음 → {@link HeadlessRenderException#upstream(String, Throwable)}. 빈 렌더·브라우저 오류.</li>
 * </ul>
 *
 * <p>전송: 요청 compress=true 면 응답이 zstd raw 바이트로 온다. 해제 분기는 요청이 아니라 응답 헤더
 * ({@code X-Encoding: zstd}) 기준 — compress 를 모르는 구버전 renderer 는 plain JSON 을 주므로, 헤더 분기여야
 * renderer 와 어느 쪽이 먼저 배포되든 안전하다. 사전({@code X-Zstd-Dict})은 {@code ZstdDictionaries} 의
 * 롤아웃 규약 참조.
 *
 * <p>SSRF: 브라우저 직행(headlessFirst) 경로는 plain fetch 를 타지 않아 그 안의 가드로 커버되지 않는다 —
 * 렌더 서비스에 URL 을 넘기기 전에 같은 판정({@link InternalHostGuard})을 거쳐, 내부망·메타데이터 주소를 사설망의
 * 실제 브라우저로 렌더시키는 구멍을 닫는다(에스컬레이션 경로에선 이중 검증 = 다층 방어).
 */
@Slf4j
@Component
public class HttpHeadlessRenderer implements HeadlessRenderer {

    private static final String RENDER_PATH = "/render";
    private static final String VERDICT_BLOCK = "BLOCK";
    private static final String ENCODING_HEADER = "X-Encoding";
    private static final String ZSTD_DICT_HEADER = "X-Zstd-Dict";
    private static final String ZSTD_ENCODING = "zstd";
    /**
     * 해제 결과(JSON = 렌더 HTML + 메타)의 안전 상한. 신뢰 경계 안(내부망 renderer)이라도 해제 폭탄·오배선을
     * 바운드하려 둔다 — 정상 압축비로는 닿지 않을 만큼 넉넉하다. 가지친 뒤 보존분 상한(maxRetainedChars)은 별도다.
     */
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
    public PageContent render(ProductLink link, boolean authorized) {
        try {
            // 여기서 걸리면 PageFetchException.blockedHost 로 — plain 경로와 같은 계약 코드로 떨어진다.
            internalHostGuard.verify(link);
            return renderVerified(link, authorized);
        } finally {
            dnsResolver.clear();
        }
    }

    private PageContent renderVerified(ProductLink link, boolean authorized) {
        HeadlessRenderResponse response = requestRender(link, authorized);

        String verdict = response.verdict();
        if (VERDICT_BLOCK.equals(verdict)) {
            log.warn("headless render verdict=BLOCK status={} url={}", response.status(), link.safeLogString());
            throw HeadlessRenderException.blocked();
        }

        String html = response.html();
        if (html == null || html.isBlank()) {
            log.warn(
                "headless render no html verdict={} status={} error={} url={}",
                verdict,
                response.status(),
                maskUrls(response.error()),
                link.safeLogString()
            );
            throw HeadlessRenderException.upstream("verdict=" + verdict + " 인데 렌더 HTML 이 없다", null);
        }

        log.info(
            "headless render verdict={} proxied={} status={} html={}chars url={}",
            verdict,
            response.proxied(),
            response.status(),
            html.length(),
            link.safeLogString()
        );

        ProductLink finalUrl = resolveFinalUrl(response.finalUrl(), link);
        // 렌더된 DOM 은 정적 fetch 보다 커질 수 있어 같은 가지치기를 통과시킨다 — 하류가 보는 문서의 모양이
        // 두 전략 사이에서 갈리지 않게 하려는 것이기도 하다.
        PruningHtmlParser.Pruned pruned =
            PruningHtmlParser.parse(html, finalUrl.value().toString(), properties.maxRetainedChars());
        if (pruned.truncated()) {
            log.warn(
                "headless render stopped at retained cap chars={} url={}",
                pruned.retainedChars(),
                link.safeLogString()
            );
        }
        return new PageContent(link, pruned.document(), finalUrl, pruned.retainedChars());
    }

    private HeadlessRenderResponse requestRender(ProductLink link, boolean authorized) {
        ResponseEntity<byte[]> entity;
        try {
            entity = restClient.post()
                .uri(RENDER_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new HeadlessRenderRequest(link.value().toString(), authorized, true, properties.compress()))
                .retrieve()
                .toEntity(byte[].class);
        } catch (RestClientResponseException e) {
            // 차단·빈 렌더는 200 + verdict 로 오므로, 렌더 서비스의 비-2xx 는 그쪽 장애·배포 중 신호다.
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
            // 압축 롤아웃 관측용 — renderer 재배포 후 압축이 실제로 도는지, 절감률이 기대치인지 확인한다.
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
     * 렌더 서비스가 redirect 를 따라간 최종 URL. Jsoup baseUri 이자 응답 계약의 finalUrl 로 호출자에게 나간다.
     * <p>형식 위반은 원본 link 로 폴백한다 — baseUri 부정확은 치명이 아니고, 여기서 INVALID_URL 을 새면 렌더는
     * 성공했는데 확정 실패로 종결되는 오판이 된다.
     * <p>단 <b>SSRF 판정은 폴백하지 않고 렌더 전체를 거부</b>한다. 원본 URL 만 검증하면 "외부 URL → 내부 주소"
     * redirect 를 렌더 서비스가 대신 따라가 준 셈이 되어, 내부망 응답이 상품 HTML 로 흘러들고 그 주소가 호출자의
     * 정체성(canonical) 입력으로까지 나간다.
     *
     * <p><b>한계 — 이 검증은 사후(post-hoc)다.</b> 렌더 응답을 받은 뒤에 도는 것이라 내부 주소로의 <i>요청 자체</i>는
     * 막지 못하고, "외부 → 내부 → 외부"로 되돌아오는 체인은 최종 URL 이 외부라 여기서 걸리지 않는다. 정적 fetch 처럼
     * hop 마다 막으려면 브라우저의 navigation 계층(자동 redirect 를 끄고 Location 마다 같은 판정) 또는 렌더 박스의
     * egress 정책이 보안 경계여야 하며, 그건 renderer repo 소관이다. 여기 검증은 "내부망 콘텐츠를 상품으로 소비하고
     * 그 주소를 호출자에게 넘기는 것"을 막는 다층 방어의 마지막 층이다.
     */
    private ProductLink resolveFinalUrl(String finalUrl, ProductLink link) {
        if (finalUrl == null || finalUrl.isBlank()) {
            return link;
        }
        ProductLink parsed;
        try {
            parsed = ProductLink.parse(finalUrl);
        } catch (ExtractionException e) {
            return link;
        }
        try {
            internalHostGuard.verify(parsed);
        } catch (PageFetchException e) {
            if (e.code() == ExtractionErrorCode.BLOCKED_HOST) {
                throw e;
            }
            // 그 외(DNS 미해결 등)는 검증 불가일 뿐 내부망 근거가 아니다 — 원본 link 로 폴백해 렌더 결과는 살린다.
            log.warn("headless render finalUrl 검증 실패 code={} url={}", e.code(), link.safeLogString());
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
