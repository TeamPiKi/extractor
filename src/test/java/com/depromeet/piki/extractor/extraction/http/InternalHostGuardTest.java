package com.depromeet.piki.extractor.extraction.http;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.depromeet.piki.extractor.common.exception.ExtractionErrorCode;
import com.depromeet.piki.extractor.domain.ProductLink;
import java.net.InetAddress;
import java.net.UnknownHostException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * SSRF 가드의 내부 대역 판정(infra 정본)과, 조회 실패를 어떤 실패로 번역하는지를 검증한다.
 */
class InternalHostGuardTest {

    private static final ProductLink LINK = ProductLink.parse("https://shop.example/p/1");

    private static InternalHostGuard guardResolvingTo(String ip) {
        return new InternalHostGuard(new RequestScopedDnsResolver(host -> new InetAddress[] {InetAddress.getByName(ip)}));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "127.0.0.1", // loopback
        "10.0.0.1", // 사설 A
        "192.168.0.1", // 사설 C
        "172.16.0.1", // 사설 B
        "169.254.0.1", // link-local
        "169.254.169.254", // 클라우드 메타데이터
        "0.0.0.0", // any-local
        "0.0.0.1", // 0.0.0.0/8 - 리눅스는 자기 서버로 보낸다
        "240.0.0.1", // 예약
        "224.0.0.1", // 멀티캐스트
        "::1", // IPv6 loopback
        "::ffff:127.0.0.1", // IPv4 를 담은 IPv6 - JDK 가 Inet4Address 로 돌려준다
        "fe80::1", // IPv6 link-local
        "fc00::1", // IPv6 ULA
        "fd00:ec2::254", // IPv6 ULA (클라우드 IPv6 메타데이터 대역)
        "100.64.0.1", // CGNAT (100.64.0.0/10)
        "100.100.100.200", // CGNAT — 일부 클라우드 메타데이터 엔드포인트
    })
    @DisplayName("내부·메타데이터 주소로 resolve 되면 SSRF 확정 실패(BLOCKED_HOST)다")
    void internalAndMetadataAddressesAreBlocked(String ip) {
        PageFetchException thrown = assertThrows(PageFetchException.class, () -> guardResolvingTo(ip).verify(LINK));

        assertEquals(ExtractionErrorCode.BLOCKED_HOST, thrown.code(), ip + " 는 차단되어야 함");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "8.8.8.8",
        "1.1.1.1",
        "93.184.216.34",
        "2606:4700:4700::1111", // 공인 IPv6 (Cloudflare)
        "198.18.0.1", // 벤치마크 대역 - 인터넷에 라우팅되지 않을 뿐 우리 서버에서 닿을 곳이 없다
    })
    @DisplayName("공인 라우팅 가능 주소는 허용된다")
    void publicRoutableAddressesAreAllowed(String ip) {
        assertDoesNotThrow(() -> guardResolvingTo(ip).verify(LINK), ip + " 는 허용되어야 함");
    }

    @Test
    @DisplayName("공인 주소와 내부 주소가 함께 오면 차단한다 — 연결이 어느 주소로 갈지 가드가 고르지 않는다")
    void mixedAnswerIsBlocked() {
        InternalHostGuard mixed = new InternalHostGuard(new RequestScopedDnsResolver(host -> new InetAddress[] {
            InetAddress.getByName("93.184.216.34"), InetAddress.getByName("10.0.1.5")}));

        PageFetchException thrown = assertThrows(PageFetchException.class, () -> mixed.verify(LINK));

        assertEquals(ExtractionErrorCode.BLOCKED_HOST, thrown.code());
    }

    @Test
    @DisplayName("host 를 조회하지 못하면 확정 실패로 던져 호출자가 재시도를 태우지 않는다")
    void unresolvableHostFailsPermanently() {
        UnknownHostException resolveFailure = new UnknownHostException("no-such-host.example");
        InternalHostGuard failingGuard = new InternalHostGuard(new RequestScopedDnsResolver(host -> {
            throw resolveFailure;
        }));

        PageFetchException thrown = assertThrows(
            PageFetchException.class,
            () -> failingGuard.verify(ProductLink.parse("https://no-such-host.example")));

        assertEquals(ExtractionErrorCode.INVALID_URL, thrown.code());
        assertTrue(thrown.permanent(), "없는 주소는 재시도해도 없으므로 확정 실패여야 함");
        assertFalse(thrown.escalatable(), "resolve 되지 않는 host 는 헤드리스도 도달하지 못함");
        assertSame(resolveFailure, thrown.getCause());
    }
}
