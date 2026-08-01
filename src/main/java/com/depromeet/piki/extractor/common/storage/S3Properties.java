package com.depromeet.piki.extractor.common.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * bucket 을 config 에 두지 않는다 — extractor 가 dev/staging/prod 세 환경 트래픽을 함께 받고 환경마다 이미지 버킷이 달라 요청별 파라미터다.
 * <p>publicBaseUrl 도 두지 않는다 — 반환 URL 은 {@link S3ImageStorage} 가 요청 bucket 과 이 region 으로 조합한다.
 */
@ConfigurationProperties(prefix = "s3")
public record S3Properties(
    @DefaultValue("ap-northeast-2") String region
) {
}
