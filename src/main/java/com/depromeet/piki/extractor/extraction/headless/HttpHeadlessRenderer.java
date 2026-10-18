package com.depromeet.piki.extractor.extraction.headless;

import com.depromeet.piki.contracts.render.v1.Hop;
import com.depromeet.piki.contracts.render.v1.RenderProto;
import com.depromeet.piki.contracts.render.v1.RenderRequest;
import com.depromeet.piki.contracts.render.v1.RenderResponse;
import com.depromeet.piki.extractor.common.exception.ExtractionException;
import com.depromeet.piki.extractor.domain.ProductLink;
import com.depromeet.piki.extractor.extraction.HeadlessExtractionProperties;
import com.depromeet.piki.extractor.extraction.http.PageFetchException;
import com.github.luben.zstd.ZstdInputStream;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * renderer 의 렌더 호출 wire 구현(계약 정본: TeamPiKi/infra 의 contracts/render.proto). 응답의 홉 목록을 {@link RenderedHop} 으로 옮긴다.
 * 홉의 해석은 하지 않는다.
 *
 * <p>내부 주소 판정은 renderer 가 브라우저의 실제 연결 IP 로 하고 {@code internal_address_hit} 로 알린다. 여기서 홉 URL 을
 * 다시 이름 해석하면 렌더와 다른 시점의 답이라 브라우저가 실제로 간 IP 와 어긋날 수 있어 하지 않는다.
 *
 * <p>전송: 요청 compress=true 면 응답이 zstd raw 바이트로 온다. 해제 분기는 요청이 아니라 응답 헤더
 * ({@code X-Encoding: zstd}) 기준 — compress 를 모르는 구버전 renderer 는 plain JSON 을 주므로, 헤더 분기여야
 * renderer 와 어느 쪽이 먼저 배포되든 안전하다. 사전({@code X-Zstd-Dict})은 {@code ZstdDictionaries} 의
 * 롤아웃 규약 참조.
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class HttpHeadlessRenderer implements HeadlessRenderer {

    private static final String RENDER_PATH = RenderProto.getDescriptor()
        .findServiceByName("RenderService")
        .findMethodByName("Render")
        .getOptions()
        .getExtension(RenderProto.httpPost);
    /** 모르는 필드를 무시해야 renderer 가 필드를 먼저 더해도 응답이 깨지지 않는다. */
    private static final JsonFormat.Parser PARSER = JsonFormat.parser().ignoringUnknownFields();
    /** false 도 찍어 무엇을 보냈는지가 와이어와 상대 로그에 남게 한다. */
    private static final JsonFormat.Printer PRINTER =
        JsonFormat.printer().alwaysPrintFieldsWithNoPresence().omittingInsignificantWhitespace();
    private static final String ENCODING_HEADER = "X-Encoding";
    private static final String ZSTD_DICT_HEADER = "X-Zstd-Dict";
    private static final String ZSTD_ENCODING = "zstd";
    /** 해제 결과(홉 전체의 JSON)의 안전 상한. 내부망이라도 해제 폭탄·오배선을 바운드하려 둔다. */
    private static final int MAX_DECOMPRESSED_BYTES = 64 * 1024 * 1024;
    private static final Pattern URL_PATTERN = Pattern.compile("https?://\\S+");

    @Qualifier(HeadlessRenderHttpClientConfig.HEADLESS_RENDER_REST_CLIENT)
    private final RestClient restClient;
    private final HeadlessExtractionProperties properties;
    private final ZstdDictionaries dictionaries;

    @Override
    public List<RenderedHop> render(ProductLink link, boolean authorized) {
        RenderResponse response = requestRender(link, authorized);
        if (!response.hasInternalAddressHit()) {
            // 홉 재검사를 하지 않으므로, 내부 주소를 판정하지 않는 renderer(배포 순서가 어긋났거나 롤백됨)의 홉은 쓸 수 없다.
            log.error("headless render without internal address judgement url={}", link.safeLogString());
            throw HeadlessRenderException.upstream("renderer 가 내부 주소를 판정하지 않는다", null);
        }
        if (response.getInternalAddressHit()) {
            log.error("[SSRF] blocked: render reached internal address url={}", link.safeLogString());
            throw PageFetchException.blockedHost();
        }
        List<Hop> hops = response.getHopsList();
        if (hops.isEmpty()) {
            String error = response.hasError() ? maskUrls(response.getError()) : null;
            log.warn("headless render no hops error={} url={}", error, link.safeLogString());
            throw HeadlessRenderException.upstream("렌더 홉이 없다: " + error, null);
        }
        log.info(
            "headless render hops={} status={} url={}",
            hops.size(),
            hops.getLast().getStatus(),
            link.safeLogString()
        );
        return hops.stream().map(hop -> toRendered(hop, link)).toList();
    }

    private RenderedHop toRendered(Hop hop, ProductLink link) {
        return new RenderedHop(
            resolveHopUrl(hop.getUrl(), link),
            hop.getStatus(),
            hop.getHeadersMap(),
            hop.getBody(),
            hop.getDom()
        );
    }

    private RenderResponse requestRender(ProductLink link, boolean authorized) {
        RenderRequest request = RenderRequest.newBuilder()
            .setUrl(link.value().toString())
            .setAuthorized(authorized)
            .setCompress(properties.compress())
            .build();
        ResponseEntity<byte[]> entity;
        try {
            entity = restClient.post()
                .uri(RENDER_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                // 바이트로 넘긴다 - 문자열 body 는 컨버터가 ISO-8859-1 로 써서 비ASCII URL 이 깨진다.
                .body(PRINTER.print(request).getBytes(StandardCharsets.UTF_8))
                .retrieve()
                .toEntity(byte[].class);
        } catch (RestClientResponseException e) {
            // 브라우저 실패는 200 + error 로 오므로, 렌더 서비스의 비-2xx 는 그쪽 장애·배포 중 신호다.
            throw HeadlessRenderException.upstream("render 서비스 응답 " + e.getStatusCode().value(), e);
        } catch (RestClientException e) {
            throw HeadlessRenderException.upstream("render 서비스 호출 실패", e);
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalStateException("렌더 요청 직렬화 실패", e);
        }
        return decode(entity);
    }

    private RenderResponse decode(ResponseEntity<byte[]> entity) {
        byte[] body = entity.getBody();
        if (body == null || body.length == 0) {
            throw HeadlessRenderException.upstream("render 응답 body 가 비어 있다", null);
        }
        if (ZSTD_ENCODING.equals(entity.getHeaders().getFirst(ENCODING_HEADER))) {
            body = decompress(body, entity.getHeaders().getFirst(ZSTD_DICT_HEADER));
        }
        RenderResponse.Builder response = RenderResponse.newBuilder();
        try {
            PARSER.merge(new InputStreamReader(new ByteArrayInputStream(body), StandardCharsets.UTF_8), response);
        } catch (IOException e) {
            throw HeadlessRenderException.upstream("render 응답 JSON 파싱 실패", e);
        }
        return response.build();
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
     * 홉 URL 은 Jsoup baseUri 이자 응답 계약의 finalUrl 로 호출자에게 나간다. 형식 위반은 원본 link 로
     * 폴백한다 — baseUri 부정확은 치명이 아니고, 여기서 INVALID_URL 을 새면 렌더는 성공했는데 확정 실패로 종결되는
     * 오판이 된다.
     */
    private ProductLink resolveHopUrl(String url, ProductLink link) {
        if (url == null || url.isBlank()) {
            return link;
        }
        try {
            return ProductLink.parseHop(url);
        } catch (ExtractionException e) {
            return link;
        }
    }

    /**
     * 렌더 서비스의 error 는 playwright 예외 원문({@code f"{type}: {e}"})이라 대상 URL 전체(쿼리스트링의 토큰 포함)가
     * 실릴 수 있다 — 로그 규약(URL 마스킹)에 맞춰 URL 패턴을 지운 채 남긴다. (package-private: 단위 테스트 대상)
     */
    static String maskUrls(String error) {
        if (error == null) {
            return null;
        }
        return URL_PATTERN.matcher(error).replaceAll("<url>");
    }
}
