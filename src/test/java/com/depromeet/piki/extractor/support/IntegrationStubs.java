package com.depromeet.piki.extractor.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

// 통합 테스트의 외부 호출 stub 빈을 한 곳에 모은다. IntegrationTestSupport 가 import 하므로 모든 통합 테스트가
// 같은 컨텍스트를 공유한다(캐시 보존). 클래스별 @TestConfiguration / @Import 로 컨텍스트를 분기하면 금지.
//
// 각 stub 은 운영 @Component 빈(HttpPageFetcher·GeminiHttpClient)과 타입이 같아 주입 후보가 2개가 된다.
// @Primary 로 stub 우선을 명시한다 — 빈 이름·파라미터명 우연 일치에 기대지 않는다.
// 이 서비스의 외부 경계는 두 개뿐이다: 대상 몰 HTTP(PageFetcher), LLM(GeminiClient). 통합 테스트는 이 둘만
// 격리하고 오케스트레이션(Fallback→plain→구조화→LLM fallback)은 실제 빈으로 탄다.
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
}
