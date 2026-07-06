package com.depromeet.piki.extractor.extraction.http;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.observation.ObservationRegistry;
import java.net.InetAddress;
import java.net.UnknownHostException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

// PIKI-Server: product/service/http/HttpPageFetcherSsrfGuardTest.kt 포팅.
// SSRF 가드의 internal-address 판정을 검증한다. redirect 가 매 hop 새 host 를 허용하므로 이 판정이 보안의 최종 방어선이다.
// 특히 IPv6 ULA(fc00::/7)는 Java 의 isSiteLocalAddress 가 못 잡아 별도로 막는다.
class HttpPageFetcherSsrfGuardTest {

    private final HttpPageFetcher fetcher =
        new HttpPageFetcher(
            new PageFetchHttpClientConfig()
                .pageFetchRestClient(ObservationRegistry.NOOP, new RequestScopedDnsResolver(), FetchProperties.defaults()),
            new RequestScopedDnsResolver(),
            FetchProperties.defaults());

    @ParameterizedTest
    @ValueSource(strings = {
        "127.0.0.1", // loopback
        "10.0.0.1", // 사설 A
        "192.168.0.1", // 사설 C
        "172.16.0.1", // 사설 B
        "169.254.0.1", // link-local
        "169.254.169.254", // 클라우드 메타데이터
        "0.0.0.0", // any-local
        "::1", // IPv6 loopback
        "fc00::1", // IPv6 ULA
        "fd00:ec2::254", // IPv6 ULA (클라우드 IPv6 메타데이터 대역)
        "100.64.0.1", // CGNAT (100.64.0.0/10)
        "100.100.100.200", // CGNAT — 일부 클라우드 메타데이터 엔드포인트
    })
    @DisplayName("내부·메타데이터 주소는 차단된다")
    void internalAndMetadataAddressesAreBlocked(String ip) throws UnknownHostException {
        assertTrue(fetcher.isInternalAddress(InetAddress.getByName(ip)), ip + " 는 차단되어야 함");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "8.8.8.8",
        "1.1.1.1",
        "93.184.216.34",
        "2606:4700:4700::1111", // 공인 IPv6 (Cloudflare)
    })
    @DisplayName("공인 라우팅 가능 주소는 허용된다")
    void publicRoutableAddressesAreAllowed(String ip) throws UnknownHostException {
        assertFalse(fetcher.isInternalAddress(InetAddress.getByName(ip)), ip + " 는 허용되어야 함");
    }
}
