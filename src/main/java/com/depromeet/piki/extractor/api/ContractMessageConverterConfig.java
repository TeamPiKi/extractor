package com.depromeet.piki.extractor.api;

import com.google.protobuf.util.JsonFormat;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverters;
import org.springframework.http.converter.protobuf.ProtobufJsonFormatHttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 계약 생성 클래스({@code extraction.proto})를 JSON 으로 읽고 쓰는 컨버터. 와이어는 JSON 그대로이고
 * 클래스만 계약에서 생성된 것으로 바뀐다 — 그래서 Jackson 이 아니라 protobuf 의 JSON 매핑이 이 타입들을 맡는다.
 *
 * <p>파서는 모르는 필드를 무시한다(tolerant reader). 기본 파서는 모르는 필드에 실패하는데, 그러면 호출자가
 * 필드를 먼저 더한 요청이 400 으로 깨져 additive-only 진화(계약 §5)가 불가능하다. 모르는 enum 문자열도 같은
 * 옵션으로 무시돼 0 값(UNSPECIFIED)으로 읽힌다.
 */
@Configuration
public class ContractMessageConverterConfig implements WebMvcConfigurer {

    @Override
    public void configureMessageConverters(HttpMessageConverters.ServerBuilder builder) {
        ProtobufJsonFormatHttpMessageConverter converter = new ProtobufJsonFormatHttpMessageConverter(
            JsonFormat.parser().ignoringUnknownFields(),
            JsonFormat.printer()
        );
        // 기본 목록(x-protobuf·text/plain·json)을 두면 Accept 가 없는 호출에 바이너리·텍스트 표현이 먼저 골라진다.
        // 이 계약의 와이어는 JSON 하나다.
        converter.setSupportedMediaTypes(List.of(MediaType.APPLICATION_JSON));
        builder.addCustomConverter(converter);
    }
}
