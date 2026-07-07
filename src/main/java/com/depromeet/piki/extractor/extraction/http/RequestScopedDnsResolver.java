package com.depromeet.piki.extractor.extraction.http;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

// SSRF 가드와 실제 HTTP 연결이 "같은 IP 를 본다"를 보장하는 요청 스코프 DNS 캐시.
//
// 가드(guardAgainstInternalHost)가 host→IP 를 한 번 조회해 사설/내부 IP 를 차단한 뒤, 실제 연결(HttpClient5 의
// DnsResolver)이 또 조회하면 그 사이 DNS 응답이 바뀔 수 있다(DNS rebinding). 그러면 가드는 공인 IP 로 통과시켰는데
// 연결은 내부 IP 로 가는 TOCTOU 구멍이 생긴다. 이 캐시는 한 fetch 동안 같은 host 를 단 한 번만 실제 조회하고
// 그 결과를 가드와 연결이 공유하게 해, "검증한 그 IP 로만 연결"을 코드 계약으로 박는다.
//
// fetch 한 번이 끝나면 clear() 로 비워 다음 요청과 격리한다. 동기 호출(HttpComponents)이라 가드와 연결이 같은
// 스레드에서 돌므로 ThreadLocal 로 요청 스코프를 표현한다.
@Component
public class RequestScopedDnsResolver {

    // host→IP 실제 조회 위임. 테스트가 가짜 IP 를 주입해 SSRF 가드/캐시 로직을 네트워크 없이 검증하도록 교체점을 연다.
    // InetAddress.getAllByName 이 checked UnknownHostException 을 던지므로
    // 전용 함수형 인터페이스로 그 시그니처를 그대로 표현한다.
    @FunctionalInterface
    public interface HostResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private final HostResolver delegate;
    private final ThreadLocal<Map<String, InetAddress[]>> cache = ThreadLocal.withInitial(HashMap::new);

    public RequestScopedDnsResolver() {
        this(InetAddress::getAllByName);
    }

    public RequestScopedDnsResolver(HostResolver delegate) {
        this.delegate = delegate;
    }

    public InetAddress[] resolve(String host) throws UnknownHostException {
        Map<String, InetAddress[]> map = cache.get();
        InetAddress[] cached = map.get(host);
        // computeIfAbsent 은 delegate 의 checked UnknownHostException 을 전파하지 못하므로 명시적 get/put 으로 캐시한다.
        if (cached != null) {
            return cached;
        }
        InetAddress[] resolved = delegate.resolve(host);
        map.put(host, resolved);
        return resolved;
    }

    public void clear() {
        cache.get().clear();
    }
}
