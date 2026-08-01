package com.depromeet.piki.extractor.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 통합 테스트의 외부 호출 stub 빈을 한 곳에 모은다. IntegrationTestSupport 가 import 하므로 모든 통합 테스트가
 * 같은 컨텍스트를 공유한다(캐시 보존) — 클래스별 {@code @TestConfiguration}/{@code @Import} 로 컨텍스트를
 * 분기하는 것은 금지다.
 *
 * <p>각 stub 은 운영 {@code @Component} 빈과 타입이 같아 주입 후보가 2개가 된다. {@code @Primary} 로 stub
 * 우선을 명시한다 — 빈 이름·파라미터명 우연 일치에 기대지 않는다.
 *
 * <p>격리 대상은 이 서비스의 외부 경계 넷뿐이다: 대상 몰 HTTP(PageFetcher), LLM(GeminiClient),
 * S3(ImageStorage), 헤드리스 렌더(HeadlessRenderer). 오케스트레이션은 실제 빈으로 탄다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class IntegrationStubs {

    @Bean
    @Primary
    StubPageFetcher pageFetcher() {
        return new StubPageFetcher();
    }

    @Bean
    @Primary
    StubGeminiClient geminiClient() {
        return new StubGeminiClient();
    }

    @Bean
    @Primary
    StubImageStorage imageStorage() {
        return new StubImageStorage();
    }

    @Bean
    @Primary
    StubHeadlessRenderer headlessRenderer() {
        return new StubHeadlessRenderer();
    }
}
