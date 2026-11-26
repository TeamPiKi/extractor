package com.depromeet.piki.extractor.image;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param decoderCommand HEIC·HEIF 를 PNG 로 바꾸는 외부 명령. 뒤에 입력·출력 경로가 붙는다. 이미지(Dockerfile)가
 *     설치하고, 명령 이름은 libheif 버전에 따라 다르다(1.19 미만은 {@code heif-convert}).
 *     <p>앞의 두 래퍼는 사용자 파일을 네이티브 디코더에 넘기는 데 대한 완화다. {@code choom} 은 메모리가 모자랄
 *     때 커널이 JVM 대신 이 프로세스를 죽이게 하고, {@code setpriv} 는 디코더가 뚫려도 서비스 권한을 못 쓰게
 *     권한 없는 사용자로 내린다.
 * @param decodeTimeout 변환 프로세스를 기다리는 상한. 넘으면 프로세스를 죽이고 크롭을 건너뛴다.
 */
@ConfigurationProperties("heif")
public record HeifProperties(
    @DefaultValue(DEFAULT_COMMAND) List<String> decoderCommand,
    @DefaultValue("10s") Duration decodeTimeout
) {

    static final String DEFAULT_COMMAND =
        "choom,-n,1000,--,setpriv,--reuid=nobody,--regid=nogroup,--clear-groups,--no-new-privs,heif-dec";

    public static HeifProperties defaults() {
        return new HeifProperties(List.of(DEFAULT_COMMAND.split(",")), Duration.ofSeconds(10));
    }
}
