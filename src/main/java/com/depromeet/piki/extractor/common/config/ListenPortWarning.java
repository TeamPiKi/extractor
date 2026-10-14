package com.depromeet.piki.extractor.common.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 배포는 서비스 포트를 env {@code PORT} 로 넘긴다(정본: TeamPiKi/infra contracts/ports.json). 없이 떴다면
 * application.yml 의 로컬 기본값으로 리슨 중이라 배포 설정이 빠진 것이다.
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class ListenPortWarning {

    private final Environment environment;

    @EventListener(ApplicationReadyEvent.class)
    void warnIfDefaultPort() {
        if (environment.getProperty("PORT") == null) {
            log.warn("PORT env 없음 - 기본 포트 {} 로 리슨", environment.getProperty("server.port"));
        }
    }
}
