package com.depromeet.piki.extractor.extraction.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import com.depromeet.piki.extractor.common.exception.ExtractionErrorCode;
import com.depromeet.piki.extractor.domain.ProductLink;
import java.net.InetAddress;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

// PIKI-Server: product/service/http/HttpPageFetcherServerErrorTest.kt 포팅.
// 대상 서버의 5xx 를 status 별로 일시(UPSTREAM_ERROR)/영구(PERMANENT_UPSTREAM)로 가르는지 네트워크 없이 검증한다.
// 봇 차단을 500(no body)으로 응답하는 쇼핑몰이 무의미하게 재시도되지 않도록 500/501 은 영구로 본다.
// 응답은 MockRestServiceServer 로 제어하고, DNS 는 가짜 공인 IP 로 주입해 SSRF 가드를 통과시킨다.
//
// 원본은 ErrorCategory/httpStatus 로 단언했으나, 이 서비스의 PageFetchException 은 code()(어느 실패)와
// permanent()(RETRYABLE↔일시 vs SERVER_ERROR/INVALID_INPUT↔확정)로 계약을 표현한다(커널 번역). 그에 맞춰 단언한다.
class HttpPageFetcherServerErrorTest {

    private final RequestScopedDnsResolver.HostResolver publicIp =
        host -> new InetAddress[] {InetAddress.getByName("93.184.216.34")};

    private HttpPageFetcher fetcherWith(Consumer<MockRestServiceServer> configure) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        configure.accept(server);
        return new HttpPageFetcher(builder.build(), new RequestScopedDnsResolver(publicIp), FetchProperties.defaults());
    }

    @Test
    @DisplayName("500·501 은 영구 실패로 보아 재시도하지 않는다")
    void status500And501ArePermanent() {
        // KREAM 처럼 봇 차단을 body 없는 500 으로 응답하는 케이스. 재시도해도 결정론적으로 재실패한다.
        for (HttpStatus status : List.of(HttpStatus.INTERNAL_SERVER_ERROR, HttpStatus.NOT_IMPLEMENTED)) {
            HttpPageFetcher fetcher =
                fetcherWith(server -> server.expect(requestTo("https://shop.example.com/p")).andRespond(withStatus(status)));

            PageFetchException ex = assertThrows(
                PageFetchException.class,
                () -> fetcher.fetch(ProductLink.parse("https://shop.example.com/p")));

            assertEquals(ExtractionErrorCode.PERMANENT_UPSTREAM, ex.code(), status + " 는 PERMANENT_UPSTREAM 이어야 함");
            assertTrue(ex.permanent(), status + " 는 확정 실패(재시도 불가)여야 함");
            assertTrue(ex.escalatable(), status + "(no-body 봇차단)는 헤드리스로 escalate 대상이어야 함");
        }
    }

    @Test
    @DisplayName("500·501 은 body 유무와 무관하게 escalatable 이다")
    void status500And501AreEscalatableRegardlessOfBody() {
        // 봇 방어가 500 에 body(캡차·차단 페이지)를 실을 수 있어 body 로 장애/차단을 가르지 않는다. 대형 몰은 상시 가용이라
        // 우리가 받는 500/501 은 대개 봇 방어다. body 있는 케이스도 escalatable=true 임을 고정해 body 의존 회귀를 막는다.
        for (HttpStatus status : List.of(HttpStatus.INTERNAL_SERVER_ERROR, HttpStatus.NOT_IMPLEMENTED)) {
            HttpPageFetcher fetcher =
                fetcherWith(server -> server.expect(requestTo("https://shop.example.com/p"))
                    .andRespond(withStatus(status).body("차단 안내 페이지 HTML")));

            PageFetchException ex = assertThrows(
                PageFetchException.class,
                () -> fetcher.fetch(ProductLink.parse("https://shop.example.com/p")));

            assertEquals(ExtractionErrorCode.PERMANENT_UPSTREAM, ex.code(), status + " body 있어도 PERMANENT_UPSTREAM");
            assertTrue(ex.permanent(), status + " body 있어도 확정 실패여야 함");
            assertTrue(ex.escalatable(), status + " body 있어도 escalate 대상이어야 함(봇 방어가 body 를 실을 수 있음)");
        }
    }

    @Test
    @DisplayName("502·503·504 는 일시 실패이되 escalate 대상이기도 하다")
    void status502To504AreRetryableAndEscalatable() {
        // permanent=false 라 flag off 시엔 워커가 plain 재시도한다. 하지만 escalatable=true 라 flag on 시엔 헤드리스로도 태운다
        // (무조건 폴백, SSRF 만 제외). 지속적 503-throttle 을 놓치지 않기 위함이고, 일시 blip 을 헤드리스로 보내는 낭비는 관측으로 조사한다.
        for (HttpStatus status : List.of(HttpStatus.BAD_GATEWAY, HttpStatus.SERVICE_UNAVAILABLE, HttpStatus.GATEWAY_TIMEOUT)) {
            HttpPageFetcher fetcher =
                fetcherWith(server -> server.expect(requestTo("https://shop.example.com/p")).andRespond(withStatus(status)));

            PageFetchException ex = assertThrows(
                PageFetchException.class,
                () -> fetcher.fetch(ProductLink.parse("https://shop.example.com/p")));

            assertEquals(ExtractionErrorCode.UPSTREAM_ERROR, ex.code(), status + " 는 UPSTREAM_ERROR 여야 함");
            assertFalse(ex.permanent(), status + " 는 일시 실패(재시도 가능)여야 함");
            assertTrue(ex.escalatable(), status + " 도 escalate 대상이어야 함(SSRF 만 제외)");
        }
    }

    @Test
    @DisplayName("4xx 는 확정 실패이되 봇 클로킹 가능성으로 escalatable 이다")
    void status4xxIsPermanentButEscalatable() {
        // 봇 방어가 404("없는 척")·403(차단)·429(throttle)로 클로킹할 수 있어, 4xx 는 헤드리스로 뚫릴 후보로 본다(무조건 폴백).
        // 입력 URL 문제로 보는 확정 실패(FETCH_CLIENT_ERROR)이나 escalatable=true.
        for (HttpStatus status : List.of(HttpStatus.FORBIDDEN, HttpStatus.NOT_FOUND, HttpStatus.GONE, HttpStatus.TOO_MANY_REQUESTS)) {
            HttpPageFetcher fetcher =
                fetcherWith(server -> server.expect(requestTo("https://shop.example.com/p")).andRespond(withStatus(status)));

            PageFetchException ex = assertThrows(
                PageFetchException.class,
                () -> fetcher.fetch(ProductLink.parse("https://shop.example.com/p")));

            assertEquals(ExtractionErrorCode.FETCH_CLIENT_ERROR, ex.code(), status + " 는 FETCH_CLIENT_ERROR 여야 함");
            assertTrue(ex.permanent(), status + " 는 확정 실패여야 함");
            assertTrue(ex.escalatable(), status + " 는 헤드리스로 escalate 대상이어야 함");
        }
    }
}
