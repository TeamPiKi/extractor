package com.depromeet.piki.extractor.extraction.headless;

import com.depromeet.piki.extractor.extraction.HeadlessExtractionProperties;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 헤드리스 렌더 서비스 호출용 RestClient 빈. HttpHeadlessRenderer 안에서 직접 만들면 테스트가 가짜 응답을
 * 끼울 수 없어(PageFetchHttpClientConfig 와 같은 이유) 빈으로 분리한다. RestClient 빈이 둘(pageFetch·이것)이므로
 * 모든 주입 지점이 {@code @Qualifier} 로 어느 쪽인지 명시한다 — 파라미터명 우연 일치에 기대면 rename 에
 * 조용히 깨진다.
 *
 * <p>헤드리스가 꺼져 있어도 빈은 뜬다 — base-url 이 비어 있어 호출하면 깨지지만, Fallback 이
 * product.extract.headless.enabled 게이트로 호출 자체를 막고, 켜져 있는데 base-url 이 비면
 * HeadlessExtractionProperties 가 부팅에서 fail-fast 한다. "호출된다 ⟹ base-url 유효" 가 성립한다.
 */
@Configuration(proxyBeanMethods = false)
public class HeadlessRenderHttpClientConfig {

    public static final String HEADLESS_RENDER_REST_CLIENT = "headlessRenderRestClient";

    @Bean(HEADLESS_RENDER_REST_CLIENT)
    public RestClient headlessRenderRestClient(
        HeadlessExtractionProperties properties,
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

    @Bean
    public ZstdDictionaries zstdDictionaries(HeadlessExtractionProperties properties) {
        return ZstdDictionaries.load(properties.zstdDictDir());
    }
}
