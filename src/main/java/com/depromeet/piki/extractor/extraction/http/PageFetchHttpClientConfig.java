package com.depromeet.piki.extractor.extraction.http;

import io.micrometer.observation.ObservationRegistry;
import java.net.InetAddress;
import java.net.UnknownHostException;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

// PIKI-Server: product/service/http/PageFetchHttpClientConfig.kt 포팅.
// 상품 페이지 fetch 용 RestClient 빈. HttpPageFetcher 안에서 직접 만들면 테스트가 가짜 응답을 끼울 수 없어,
// 생성을 이 Config 로 분리해 주입한다(MockRestServiceServer 로 redirect 루프를 검증하기 위함).
//
// SSRF IP-pin: HttpClient5 의 DnsResolver 에 RequestScopedDnsResolver 를 끼워, 가드가 검증한 그 IP 로만 연결하게 한다.
// HttpClient5 는 IP 만 이 resolver 가 정한 값으로 바꾸고 TLS SNI·인증서 hostname 검증·Host 헤더는 원 도메인을
// 유지하므로, "검증된 IP 로 연결 + 도메인 정체성 유지"가 양립한다. JDK HttpURLConnection 으로는 SNI·인증서를 수동
// 복구해야 해(JDK-8144566 등) 깨지기 쉬워 HttpComponents 로 교체했다.
@Configuration
public class PageFetchHttpClientConfig {

    @Bean
    public RestClient pageFetchRestClient(
        ObservationRegistry observationRegistry,
        RequestScopedDnsResolver dnsResolver,
        FetchProperties properties
    ) {
        // host→IP 해석을 RequestScopedDnsResolver 에 위임한다. 가드(guardAgainstInternalHost)도 같은 인스턴스를 쓰므로
        // 한 fetch 동안 host 당 단 한 번만 실제 조회하고, 가드가 검증한 그 IP 로 연결이 이뤄진다(DNS rebinding 차단).
        DnsResolver httpClientDnsResolver = new DnsResolver() {
            @Override
            public InetAddress[] resolve(String host) throws UnknownHostException {
                return dnsResolver.resolve(host);
            }

            @Override
            public String resolveCanonicalHostname(String host) throws UnknownHostException {
                return SystemDefaultDnsResolver.INSTANCE.resolveCanonicalHostname(host);
            }
        };
        PoolingHttpClientConnectionManager connectionManager =
            PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(httpClientDnsResolver)
                .setDefaultConnectionConfig(
                    ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.ofMilliseconds(properties.connectTimeout().toMillis()))
                        .build())
                .build();
        CloseableHttpClient httpClient =
            HttpClients.custom()
                .setConnectionManager(connectionManager)
                // HttpClient5 는 GET 301/302/307 을 기본 자동 추적한다. 우리는 매 hop SSRF·도메인 검증을 끼우려 수동으로
                // 따라가므로(JDK 의 instanceFollowRedirects=false 등가물), 라이브러리 자동 추적을 끈다. 끄지 않으면
                // HttpPageFetcher.nextRedirect 의 cross-domain·다운그레이드 차단이 우회된다.
                .disableRedirectHandling()
                .setDefaultRequestConfig(
                    RequestConfig.custom()
                        // connectionRequestTimeout 은 미설정 시 기본 3분이라, 풀 고갈·느린 upstream 에서 워커가 오래 대기한다.
                        // 빠르게 실패시켜 상위가 적절히 처리·재시도하게 둔다.
                        .setConnectionRequestTimeout(Timeout.ofMilliseconds(properties.connectionRequestTimeout().toMillis()))
                        .setResponseTimeout(Timeout.ofMilliseconds(properties.readTimeout().toMillis()))
                        .build())
                .build();
        return RestClient.builder()
            .requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient))
            .defaultHeader(HttpHeaders.USER_AGENT, properties.userAgent())
            .defaultHeader(HttpHeaders.ACCEPT, "text/html,application/xhtml+xml,*/*;q=0.8")
            .defaultHeader(HttpHeaders.ACCEPT_LANGUAGE, "ko,en;q=0.9")
            // 상품 페이지 fetch 가 trace 의 한 구간(HTTP client span)으로 잡히게 한다. RestClient 레벨이라 factory 교체와 무관.
            .observationRegistry(observationRegistry)
            .build();
    }
}
