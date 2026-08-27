package com.depromeet.piki.extractor.extraction.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.observation.ObservationRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.hc.core5.http.ConnectionClosedException;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.NoHttpResponseException;
import org.apache.hc.core5.http.message.BasicClassicHttpRequest;
import org.apache.hc.core5.http.message.BasicHttpResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * 재시도 층 분리를 못 박는다. 경계는 <b>플랫폼 서버가 우리 요청을 봤는가</b> 하나이고, 재전송 안전이 예외
 * 이름만으로 보증되는 NoHttpResponseException(응답 0바이트 종료)만 여기서 한 번 복구한다. 나머지는 전부 호출자(core)의
 * 작업 큐가 소유한다. 두 축이 조용히 뒤집혀도 컴파일·기동은 멀쩡하므로 값 자체를 고정한다. 실제로 HttpClient5
 * 기본값(429·503·리셋까지 재시도)이 살아 있는 걸 아무도 못 본 기간이 있었다.
 */
class PageFetchHttpClientRetryTest {

    private final PreDeliveryRetryStrategy strategy = new PreDeliveryRetryStrategy();

    // --- 닿기 전 끊김: 여기서 한 번 복구한다 -----------------------------------

    @Test
    @DisplayName("응답 0바이트로 연결이 닫히면 한 번 다시 붙는다 - 재전송 안전이 예외 이름만으로 보증되는 유일한 경우")
    void recoversWhenConnectionDiesBeforeDelivery() {
        NoHttpResponseException e = new NoHttpResponseException("target failed to respond");

        assertTrue(strategy.retryRequest(get(), e, 1, null));
        assertFalse(strategy.retryRequest(get(), e, 2, null), "복구는 한 번뿐");
    }

    // --- 모호하거나 플랫폼 서버가 봤을 수 있는 실패: 전부 core 작업 큐 소관 --------

    @Test
    @DisplayName("리셋은 복구하지 않는다 - 차단측이 능동적으로 보낸 RST 와 구분할 수 없다")
    void doesNotRecoverOnConnectionReset() {
        assertFalse(strategy.retryRequest(get(), new SocketException("Connection reset"), 1, null));
    }

    @Test
    @DisplayName("응답 도중 끊김은 복구하지 않는다 - 플랫폼 서버가 일한 뒤일 수 있다")
    void doesNotRecoverOnPrematureClose() {
        assertFalse(strategy.retryRequest(get(), new ConnectionClosedException("premature end"), 1, null));
    }

    @Test
    @DisplayName("연결 자체를 못 세우면 다시 붙지 않는다 - 즉시 재시도해도 같은 결과라 지연만 남는다")
    void doesNotRecoverWhenConnectionCannotBeEstablished() {
        assertFalse(strategy.retryRequest(get(), new ConnectException("refused"), 1, null));
        assertFalse(strategy.retryRequest(get(), new java.net.UnknownHostException("nx"), 1, null));
        assertFalse(strategy.retryRequest(get(), new javax.net.ssl.SSLException("handshake"), 1, null));
    }

    @Test
    @DisplayName("읽기 타임아웃은 복구하지 않는다 - 플랫폼 서버가 이미 받아 처리 중일 수 있다")
    void doesNotRecoverOnReadTimeout() {
        assertFalse(strategy.retryRequest(get(), new InterruptedIOException("read timed out"), 1, null));
    }

    @Test
    @DisplayName("비멱등 메서드는 자동 반복하지 않는다 (RFC 9110 9.2.2)")
    void doesNotRetryNonIdempotentMethods() {
        BasicClassicHttpRequest post = new BasicClassicHttpRequest("POST", "/p");

        assertFalse(strategy.retryRequest(post, new NoHttpResponseException("x"), 1, null));
    }

    @Test
    @DisplayName("응답을 받은 요청은 여기서 재시도하지 않는다 - 429·503 도 상위 큐가 소유한다")
    void neverRetriesOnceTheMallResponded() {
        for (int status : new int[] {429, 503, 500, 403}) {
            HttpResponse response = new BasicHttpResponse(status);

            assertFalse(strategy.retryRequest(response, 1, null), status + " 는 상위 큐 소관");
        }
    }

    // --- 실제 소켓: 설정이 클라이언트에 실제로 물렸는가 ---------------------------

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    @DisplayName("실제 클라이언트가 응답 0바이트 종료에 정확히 한 번 더 붙는다")
    void wiredClientRetriesExactlyOnceOnCloseWithoutResponse() throws Exception {
        AtomicInteger connections = new AtomicInteger();

        try (ServerSocket server = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            serveEachConnection(server, socket -> {
                connections.incrementAndGet();
                drainRequestHead(socket);   // 요청을 다 읽은 뒤 응답 없이 닫는다(FIN) -> NoHttpResponseException
            });

            assertThrows(ResourceAccessException.class, () -> fetch(server));

            // 최초 1회 + 복구 1회. 이 숫자가 1이면 복구가 죽은 것이고, 3 이상이면 상한이 풀린 것이다.
            assertEquals(2, connections.get());
        }
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    @DisplayName("실제 클라이언트가 리셋(RST)에는 다시 붙지 않는다")
    void wiredClientDoesNotRetryOnReset() throws Exception {
        AtomicInteger connections = new AtomicInteger();

        try (ServerSocket server = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            serveEachConnection(server, socket -> {
                connections.incrementAndGet();
                socket.setSoLinger(true, 0);   // close 가 RST 를 보내 차단측 리셋과 같은 모양을 만든다
            });

            assertThrows(ResourceAccessException.class, () -> fetch(server));

            assertEquals(1, connections.get());
        }
    }

    private static void fetch(ServerSocket server) {
        RestClient client = new PageFetchHttpClientConfig()
            .pageFetchRestClient(ObservationRegistry.NOOP, loopbackResolver(), FetchProperties.defaults());
        client.get()
            .uri("http://127.0.0.1:" + server.getLocalPort() + "/p")
            .retrieve()
            .body(String.class);
    }

    /** 커넥션마다 handler 를 한 번 적용하고 닫는 단순 서버. accept 루프는 데몬 스레드로 돈다. */
    private static void serveEachConnection(ServerSocket server, SocketHandler handler) {
        Thread accepter = new Thread(() -> {
            while (!server.isClosed()) {
                try (Socket socket = server.accept()) {
                    handler.handle(socket);
                } catch (IOException e) {
                    return;
                }
            }
        });
        accepter.setDaemon(true);
        accepter.start();
    }

    /** 요청 헤더 끝(CRLFCRLF)까지 읽는다. GET 이라 본문은 없다. */
    private static void drainRequestHead(Socket socket) throws IOException {
        InputStream in = socket.getInputStream();
        int tail = 0;
        int b;
        while ((b = in.read()) != -1) {
            tail = (tail << 8) | b;
            if (tail == 0x0D0A0D0A) {
                return;
            }
        }
    }

    @FunctionalInterface
    private interface SocketHandler {
        void handle(Socket socket) throws IOException;
    }

    private static BasicClassicHttpRequest get() {
        return new BasicClassicHttpRequest("GET", "/p");
    }

    private static RequestScopedDnsResolver loopbackResolver() {
        return new RequestScopedDnsResolver(host -> new InetAddress[] {InetAddress.getLoopbackAddress()});
    }
}
