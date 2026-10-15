package com.depromeet.piki.extractor.extraction.http;

import com.depromeet.piki.extractor.domain.ProductLink;
import java.net.InetAddress;
import java.net.UnknownHostException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * SSRF 가드: host 가 내부 대역({@link InternalAddressRanges})으로 resolve 되면 차단한다. 직접 가져오기(HttpPageFetcher)가 매
 * redirect hop 마다 부른다. 렌더 경로는 renderer 가 브라우저의 실제 연결 IP 로 같은 정본을 판정하므로 여기서 다시 보지 않는다.
 *
 * <p>빈이 아니라 소유자가 자기 RequestScopedDnsResolver 로 직접 조립한다 — 가드가 resolve 한 그 IP 를 이후 연결이
 * 공유(IP pin)하는 계약은 "같은 resolver 인스턴스" 로만 성립하므로, 조립 책임을 소유자에게 둬 그 결합을 명시한다.
 */
@Slf4j
@RequiredArgsConstructor
public final class InternalHostGuard {

    private final RequestScopedDnsResolver dnsResolver;
    private final InternalAddressRanges ranges = InternalAddressRanges.CONTRACT_RANGES;

    public void verify(ProductLink link) {
        InetAddress[] addresses;
        try {
            addresses = dnsResolver.resolve(link.value().getHost());
        } catch (UnknownHostException e) {
            log.info("link fetch unknown host url={}", link.safeLogString());
            throw PageFetchException.unresolvableHost(e);
        }
        for (InetAddress addr : addresses) {
            if (ranges.contains(addr)) {
                log.error("[SSRF] blocked: internal address resolved url={}", link.safeLogString());
                throw PageFetchException.blockedHost();
            }
        }
    }
}
