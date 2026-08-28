package com.depromeet.piki.extractor.extraction.http;

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
 * SSRF 가드의 internal-address 판정과, 조회 실패를 어떤 실패로 번역하는지를 검증한다.
 *
 * <p>redirect 가 매 hop 새 host 를 허용하고 헤드리스 직행 경로도 이 판정을 거치므로, 이 판정이 보안의 최종 방어선이다.
 * 특히 IPv6 ULA(fc00::/7)는 Java 의 {@code isSiteLocalAddress} 가 못 잡아 별도로 막는다.
 */
class InternalHostGuardTest {

    private final InternalHostGuard guard = new InternalHostGuard(new RequestScopedDnsResolver());

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
        assertTrue(guard.isInternalAddress(InetAddress.getByName(ip)), ip + " 는 차단되어야 함");
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
        assertFalse(guard.isInternalAddress(InetAddress.getByName(ip)), ip + " 는 허용되어야 함");
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
