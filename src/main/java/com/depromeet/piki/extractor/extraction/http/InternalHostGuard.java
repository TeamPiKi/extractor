package com.depromeet.piki.extractor.extraction.http;

import com.depromeet.piki.extractor.domain.ProductLink;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * SSRF 가드: host 가 외부 라우팅 불가 주소(loopback/사설/링크로컬/메타데이터/IPv6 ULA/CGNAT)로 resolve 되면 차단.
 * 외부 쇼핑몰만 접근하는 것이 추출 파이프라인의 책임이라 외부 라우팅 가능 IP 만 허용한다.
 *
 * <p>HttpPageFetcher(정적 fetch, 매 redirect hop)와 HttpHeadlessRenderer(브라우저 직행 경로)가 공유한다 —
 * 직행 경로는 plain fetch 를 아예 타지 않아 fetch 안의 가드로는 커버되지 않으므로, 렌더 서비스로 URL 을
 * 넘기기 전에 같은 판정을 거쳐야 내부망에 헤드리스 브라우저를 겨누는 구멍이 닫힌다.
 *
 * <p>빈이 아니라 소유자가 자기 RequestScopedDnsResolver 로 직접 조립한다 — 가드가 resolve 한 그 IP 를 이후 연결이
 * 공유(IP pin)하는 계약은 "같은 resolver 인스턴스" 로만 성립하므로, 조립 책임을 소유자에게 둬 그 결합을 명시한다.
 */
@Slf4j
@RequiredArgsConstructor
public final class InternalHostGuard {

    private final RequestScopedDnsResolver dnsResolver;

    public void verify(ProductLink link) {
        String host = link.value().getHost();
        if (host == null) {
            log.warn("[SSRF] blocked: missing host url={}", link.safeLogString());
            throw PageFetchException.blockedHost();
        }
        InetAddress[] addresses;
        try {
            addresses = dnsResolver.resolve(host);
        } catch (UnknownHostException e) {
            log.info("link fetch unknown host url={}", link.safeLogString());
            throw PageFetchException.unresolvableHost(e);
        }
        for (InetAddress addr : addresses) {
            if (isInternalAddress(addr)) {
                log.error("[SSRF] blocked: internal address resolved url={}", link.safeLogString());
                throw PageFetchException.blockedHost();
            }
        }
    }

    /**
     * 외부 라우팅 불가 주소면 SSRF 위험으로 본다. Java 의 isSiteLocalAddress 는 IPv4 사설만 잡고 IPv6 ULA(fc00::/7)는
     * 못 잡으므로(GCP·Alibaba 등의 IPv6 메타데이터·내부망), 이를 별도로 차단한다.
     * (같은 패키지 테스트가 직접 호출하도록 package-private.)
     */
    boolean isInternalAddress(InetAddress addr) {
        return addr.isLoopbackAddress()
            || addr.isAnyLocalAddress()
            || addr.isSiteLocalAddress()
            || addr.isLinkLocalAddress()
            || addr.isMulticastAddress()
            || isUniqueLocalIpv6(addr)
            || isCarrierGradeNatIpv4(addr)
            // AWS / GCP IPv4 메타데이터(169.254.169.254 는 link-local 이라 위에서 잡히지만 방어적으로 명시).
            || "169.254.169.254".equals(addr.getHostAddress());
    }

    /** IPv6 Unique Local Address(fc00::/7). 첫 바이트의 상위 7비트가 1111110 (0xfc 또는 0xfd)이면 ULA 다. */
    private boolean isUniqueLocalIpv6(InetAddress addr) {
        if (!(addr instanceof Inet6Address)) {
            return false;
        }
        byte[] bytes = addr.getAddress();
        int first = bytes.length == 0 ? 0 : bytes[0];
        return (first & 0xfe) == 0xfc;
    }

    /**
     * IPv4 Carrier-Grade NAT(100.64.0.0/10). 외부 라우팅이 안 되는 캐리어 NAT 대역으로, 일부 클라우드
     * 메타데이터 엔드포인트(예: 100.100.100.200)가 여기 속해 SSRF 차단 대상이다.
     */
    private boolean isCarrierGradeNatIpv4(InetAddress addr) {
        if (!(addr instanceof Inet4Address)) {
            return false;
        }
        byte[] bytes = addr.getAddress();
        int first = bytes[0] & 0xFF;
        int second = bytes[1] & 0xFF;
        return first == 100 && second >= 64 && second <= 127;
    }
}
