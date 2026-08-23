package com.depromeet.piki.extractor.extraction.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.micrometer.observation.ObservationRegistry;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * 페이지 fetch 클라이언트가 I/O 오류를 스스로 재시도하지 않는지 **실제 소켓으로** 검증한다.
 *
 * <p>HttpClient5 는 이 재시도를 기본으로 켜 둔다(maxRetries=1, 대기 0ms). 파싱 재시도는 호출자(core)의 작업 큐
 * 한 곳에만 두는 것이 이 서비스의 방침이라 그 기본값을 끄는데, 끄는 코드가 사라져도 컴파일·기동·대부분의 테스트는
 * 멀쩡하다. 실제로 이 기본값이 살아 있는 걸 아무도 못 본 채 fetch 최악 시간이 두 배로 돌던 기간이 있었다.
 *
 * <p>MockRestServiceServer 로는 못 잡는다 — 그건 RestClient 위에 붙어 HttpClient 를 아예 타지 않는다.
 * 그래서 연결을 받자마자 끊는 로컬 소켓을 두고 **도착한 연결 수**를 센다.
 */
class PageFetchHttpClientRetryTest {

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    @DisplayName("연결이 끊겨도 클라이언트가 스스로 다시 붙지 않는다 — 재시도는 호출자의 큐 한 곳에만 있다")
    void doesNotRetryOnIoError() throws Exception {
        AtomicInteger connections = new AtomicInteger();

        try (ServerSocket server = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            Thread accepter = new Thread(() -> {
                while (!server.isClosed()) {
                    try (Socket socket = server.accept()) {
                        connections.incrementAndGet();
                        socket.setSoLinger(true, 0);   // RST 로 끊어 클라이언트에 I/O 오류를 준다
                    } catch (IOException e) {
                        return;                        // 서버 종료
                    }
                }
            });
            accepter.setDaemon(true);
            accepter.start();

            RestClient client = new PageFetchHttpClientConfig()
                .pageFetchRestClient(ObservationRegistry.NOOP, loopbackResolver(), FetchProperties.defaults());

            assertThrows(
                ResourceAccessException.class,
                () -> client.get()
                    .uri("http://127.0.0.1:" + server.getLocalPort() + "/p")
                    .retrieve()
                    .body(String.class));

            assertEquals(1, connections.get(), "재시도가 켜져 있으면 같은 곳에 2번 붙는다");
        }
    }

    private static RequestScopedDnsResolver loopbackResolver() {
        return new RequestScopedDnsResolver(host -> new InetAddress[] {InetAddress.getLoopbackAddress()});
    }
}
