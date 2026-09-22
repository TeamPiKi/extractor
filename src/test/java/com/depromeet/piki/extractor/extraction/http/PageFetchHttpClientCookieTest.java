package com.depromeet.piki.extractor.extraction.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.observation.ObservationRegistry;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.web.client.RestClient;

/**
 * 쿠키 배선이 실제 클라이언트에서 도는지 소켓까지 내려가 확인한다. 다른 fetch 테스트가 쓰는
 * MockRestServiceServer 는 요청 팩토리를 통째로 대신하므로 HttpClient 의 쿠키 exec 체인을 타지 않는다 —
 * {@code setDefaultCookieStore} 가 빠지거나 쿠키 처리가 꺼져도 그 테스트들은 전부 초록이다.
 */
class PageFetchHttpClientCookieTest {

    /** Connection: close 로 매 요청이 새 커넥션을 열게 한다 — accept 한 번에 요청 하나로 단순화. */
    private static final String SET_COOKIE_302 =
        "HTTP/1.1 302 Found\r\nLocation: /next\r\nSet-Cookie: bounce=1; Path=/\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";

    private static String page() {
        String body = "<html>ok</html>";
        return "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: " + body.length()
            + "\r\nConnection: close\r\n\r\n" + body;
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    @DisplayName("앞 홉이 심은 쿠키가 다음 홉 요청에 실린다 - 쿠키를 심고 자기 자신으로 보내는 딥링크 바운스가 이걸로 풀린다")
    void cookieFromEarlierHopRidesTheNextHop() throws Exception {
        List<String> requestHeads = new CopyOnWriteArrayList<>();
        RequestScopedCookieStore cookieStore = new RequestScopedCookieStore();

        try (ServerSocket server = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            serve(server, requestHeads, List.of(SET_COOKIE_302, page()));
            RestClient client = client(cookieStore);

            get(client, server, "/p");
            get(client, server, "/next");

            assertEquals(2, requestHeads.size());
            assertFalse(requestHeads.get(0).contains("Cookie:"), "첫 요청은 빈 손이어야 한다");
            assertTrue(requestHeads.get(1).contains("Cookie: bounce=1"), requestHeads.get(1));
        }
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    @DisplayName("fetch 가 끝나 저장소를 비우면 다음 요청은 쿠키 없이 나간다")
    void clearedStoreSendsNoCookie() throws Exception {
        List<String> requestHeads = new CopyOnWriteArrayList<>();
        RequestScopedCookieStore cookieStore = new RequestScopedCookieStore();

        try (ServerSocket server = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            serve(server, requestHeads, List.of(SET_COOKIE_302, page()));
            RestClient client = client(cookieStore);

            get(client, server, "/p");
            cookieStore.clear();
            get(client, server, "/next");

            assertEquals(2, requestHeads.size());
            assertFalse(requestHeads.get(1).contains("Cookie:"), requestHeads.get(1));
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("빌더 기본 저장소는 스레드를 넘어 쿠키를 싣고 요청 스코프 저장소는 싣지 않는다 - 이 저장소가 있는 이유")
    void defaultStoreLeaksAcrossThreadsAndScopedDoesNot() throws Exception {
        // 기본 클라이언트는 우리 DNS 를 안 거치므로 loopback 을 직접 친다. 그 차이는 쿠키 판정과 무관하다.
        assertTrue(ridesOnAnotherThread(builderDefaultClient(), "127.0.0.1"), "지정 없는 기본 저장소는 샌다");
        assertFalse(ridesOnAnotherThread(client(new RequestScopedCookieStore()), "cookie.test"), "요청 스코프는 안 샌다");
    }

    /** 한 요청이 쿠키를 받은 뒤, 다른 스레드가 보낸 요청에 그 쿠키가 실리는가. */
    private static boolean ridesOnAnotherThread(RestClient client, String host) throws Exception {
        List<String> heads = new CopyOnWriteArrayList<>();
        try (ServerSocket server = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            serve(server, heads, List.of(SET_COOKIE_302, page()));
            String base = "http://" + host + ":" + server.getLocalPort();
            client.get().uri(base + "/p").retrieve().body(String.class);
            Thread other = new Thread(() -> client.get().uri(base + "/next").retrieve().body(String.class));
            other.start();
            other.join();
            return heads.get(1).contains("Cookie: bounce");
        }
    }

    /** 저장소를 지정하지 않은 조립 - 빌더가 자기 기본 저장소를 끼운다. */
    private static RestClient builderDefaultClient() {
        return RestClient.builder()
            .requestFactory(new HttpComponentsClientHttpRequestFactory(
                HttpClients.custom().disableRedirectHandling().build()))
            .build();
    }

    /** 운영과 같은 조립 — DNS 만 loopback 으로 돌려 실제 클라이언트를 그대로 쓴다. */
    private static RestClient client(RequestScopedCookieStore cookieStore) {
        return new PageFetchHttpClientConfig().pageFetchRestClient(
            ObservationRegistry.NOOP,
            new RequestScopedDnsResolver(host -> new InetAddress[] {InetAddress.getLoopbackAddress()}),
            cookieStore,
            FetchProperties.defaults());
    }

    private static void get(RestClient client, ServerSocket server, String path) {
        client.get()
            .uri("http://cookie.test:" + server.getLocalPort() + path)
            .retrieve()
            .body(String.class);
    }

    /** 커넥션마다 요청 헤드를 기록하고 준비된 응답을 순서대로 돌려준다. accept 루프는 데몬 스레드. */
    private static void serve(ServerSocket server, List<String> requestHeads, List<String> responses) {
        Thread accepter = new Thread(() -> {
            int served = 0;
            while (!server.isClosed()) {
                try (Socket socket = server.accept()) {
                    requestHeads.add(readHead(socket));
                    String response = responses.get(Math.min(served++, responses.size() - 1));
                    socket.getOutputStream().write(response.getBytes(StandardCharsets.UTF_8));
                    socket.getOutputStream().flush();
                } catch (IOException e) {
                    return;
                }
            }
        });
        accepter.setDaemon(true);
        accepter.start();
    }

    /** 요청 헤더 끝(CRLFCRLF)까지 읽어 문자열로. GET 이라 본문은 없다. */
    private static String readHead(Socket socket) throws IOException {
        InputStream in = socket.getInputStream();
        StringBuilder head = new StringBuilder();
        int tail = 0;
        int b;
        while ((b = in.read()) != -1) {
            head.append((char) b);
            tail = (tail << 8) | b;
            if (tail == 0x0D0A0D0A) {
                break;
            }
        }
        return head.toString();
    }
}
