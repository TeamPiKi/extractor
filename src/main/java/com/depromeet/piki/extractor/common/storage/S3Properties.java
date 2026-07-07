package com.depromeet.piki.extractor.common.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

// bucket 은 요청별 파라미터다(extractor 가 dev/staging/prod 세 환경 트래픽을 받고 환경마다 이미지 버킷이 다르다) — config 에 두지 않는다.
// publicBaseUrl 도 두지 않는다 — 반환 URL 은 S3ImageStorage 가 bucket+region 으로 조합한다(https://{bucket}.s3.{region}.amazonaws.com/{key}).
// region 은 미지정 시 ap-northeast-2.
@ConfigurationProperties(prefix = "s3")
public record S3Properties(
    @DefaultValue("ap-northeast-2") String region
) {
}
