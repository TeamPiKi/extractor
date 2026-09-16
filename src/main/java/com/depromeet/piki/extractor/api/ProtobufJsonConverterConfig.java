package com.depromeet.piki.extractor.api;

import com.google.protobuf.util.JsonFormat;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverters;
import org.springframework.http.converter.protobuf.ProtobufJsonFormatHttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 계약 생성 클래스를 JSON 으로 읽고 쓰는 컨버터.
 *
 * <p>기본 파서는 모르는 필드에 실패한다 — 그대로 두면 호출자가 필드를 먼저 더한 요청이 400 으로 깨져
 * additive-only 진화가 막힌다. 모르는 enum 문자열도 같은 옵션으로 UNSPECIFIED 로 읽힌다.
 */
@Configuration
public class ProtobufJsonConverterConfig implements WebMvcConfigurer {

    @Override
    public void configureMessageConverters(HttpMessageConverters.ServerBuilder builder) {
        ProtobufJsonFormatHttpMessageConverter converter = new ProtobufJsonFormatHttpMessageConverter(
            JsonFormat.parser().ignoringUnknownFields(),
            JsonFormat.printer()
        );
        // 기본 목록을 두면 Accept 없는 호출에 텍스트 표현이 먼저 골라진다 - 통합 테스트 22건 실패로 드러났다.
        converter.setSupportedMediaTypes(List.of(MediaType.APPLICATION_JSON));
        builder.addCustomConverter(converter);
    }
}
