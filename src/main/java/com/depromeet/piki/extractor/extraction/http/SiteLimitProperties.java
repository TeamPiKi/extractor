package com.depromeet.piki.extractor.extraction.http;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 사이트별 동시 fetch 상한({@link SiteConcurrencyLimiter}).
 *
 * @param maxWait 자리를 기다리는 최대 시간. 링크 추출 기한(호출자 read 타임아웃 55초) 안에 fetch·LLM 몫을 남긴다.
 */
@ConfigurationProperties("fetch.site-limit")
public record SiteLimitProperties(
    @DefaultValue("2") int maxConcurrent,
    @DefaultValue("15s") Duration maxWait
) {
}
