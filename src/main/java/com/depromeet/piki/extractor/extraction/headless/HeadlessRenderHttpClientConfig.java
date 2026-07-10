package com.depromeet.piki.extractor.extraction.headless;

import com.depromeet.piki.extractor.extraction.HeadlessExtractionProperties;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

// 헤드리스 렌더 서비스 호출용 RestClient 빈. HttpHeadlessRenderer 안에서 직접 만들면 테스트가 가짜 응답을
// 끼울 수 없어(PageFetchHttpClientConfig 와 같은 이유) 빈으로 분리한다. RestClient 빈이 둘(pageFetch·이것)이므로
// 모든 주입 지점이 @Qualifier 로 어느 쪽인지 명시한다 — 파라미터명 우연 일치에 기대면 rename 에 조용히 깨진다.
//
// enabled=false(기본)여도 빈은 뜬다 — base-url 이 비어 있어 호출하면 깨지지만, Fallback 이
// product.extract.headless.enabled 게이트로 호출 자체를 막고, enabled=true 인데 base-url 이 비면
// HeadlessExtractionProperties 가 부팅에서 fail-fast 한다. "호출된다 ⟹ base-url 유효" 가 성립한다.
@Configuration(proxyBeanMethods = false)
public class HeadlessRenderHttpClientConfig {

    public static final String HEADLESS_RENDER_REST_CLIENT = "headlessRenderRestClient";

    @Bean(HEADLESS_RENDER_REST_CLIENT)
    public RestClient headlessRenderRestClient(
        HeadlessExtractionProperties properties,
        // 렌더 호출이 trace 의 한 구간(HTTP client span)으로 잡히게 한다 — 브라우저 렌더 latency 가 막대로 보인다.
        ObservationRegistry observationRegistry
    ) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.readTimeout());
        return RestClient.builder()
            .baseUrl(properties.baseUrl())
            .requestFactory(requestFactory)
            .observationRegistry(observationRegistry)
            .build();
    }

    // zstd 압축 응답 해제용 사전 보관소. 부팅 시 디렉토리를 읽어 오설정을 fail-fast 로 드러낸다
    // (기본값 zstd-dict-dir="" 는 사전 없음 — 파일 IO 없이 빈 보관소).
    @Bean
    public ZstdDictionaries zstdDictionaries(HeadlessExtractionProperties properties) {
        return ZstdDictionaries.load(properties.zstdDictDir());
    }
}
