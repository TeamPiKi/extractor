package com.depromeet.piki.extractor.common.config;

import io.micrometer.observation.ObservationPredicate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.ServerRequestObservationContext;

// 관측 노이즈를 observation 단계에서 제외한다 - predicate 가 false 면 그 observation 이 NOOP 이 되어
// span·메트릭이 아예 생기지 않는다. Spring Boot 가 @Bean ObservationPredicate 를 ObservationRegistry 에
// 자동 적용한다. (core 의 ObservationConfig 와 같은 패턴, #12)
//
// 제외 대상은 actuator 요청 하나다: 박스 Alloy 가 15초마다 긁는 /actuator/prometheus scrape 가
// 서버 트레이스로 export 되어 Tempo 를 수집기 트래픽으로 채운다 - 우리 API 가 아니라 노이즈다.
// 실제 API(/extract 등) 관측과 Gemini·헤드리스 클라이언트 관측은 그대로 둔다.
@Configuration
public class ObservationConfig {

    @Bean
    ObservationPredicate filterNoiseObservations() {
        return (name, context) -> {
            if (context instanceof ServerRequestObservationContext serverContext) {
                var request = serverContext.getCarrier();
                return request == null || !request.getRequestURI().startsWith("/actuator");
            }
            return true;
        };
    }
}
