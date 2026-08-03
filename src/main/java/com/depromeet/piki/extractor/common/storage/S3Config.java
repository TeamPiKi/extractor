package com.depromeet.piki.extractor.common.storage;

import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/** 이미지(OCR) 경로용 S3Client 빈. presign 을 하지 않으므로 S3Presigner 빈은 없다. */
@Configuration
public class S3Config {

    /** 자격증명은 {@code DefaultCredentialsProvider} 체인으로 해결한다 — EC2 는 instance role, 로컬은 환경변수/프로파일. */
    @Bean
    public S3Client s3Client(S3Properties s3Properties) {
        return S3Client.builder()
            .region(Region.of(s3Properties.region()))
            .overrideConfiguration(
                ClientOverrideConfiguration.builder()
                    .apiCallTimeout(Duration.ofSeconds(10))
                    .apiCallAttemptTimeout(Duration.ofSeconds(5))
                    .build())
            .build();
    }
}
