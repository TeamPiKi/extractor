package com.depromeet.piki.extractor.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.depromeet.piki.contracts.extraction.v1.ExtractionMethod;
import com.depromeet.piki.contracts.extraction.v1.ExtractionResult;
import com.depromeet.piki.contracts.extraction.v1.LinkExtractionRequest;
import com.google.protobuf.util.JsonFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 계약 생성 클래스가 JSON 와이어에서 지키는 성질. 세 가지가 계약의 일부라 여기서 못 박는다 — 누락 필드의
 * 안전 기본값(fail-safe), 모르는 필드·enum 값의 무시(tolerant reader), 안 채운 값의 생략.
 * 파서·프린터 구성은 {@link ContractMessageConverterConfig} 와 같아야 하므로 같은 옵션을 그대로 쓴다.
 */
class ContractWireTest {

    private static final JsonFormat.Parser PARSER = JsonFormat.parser().ignoringUnknownFields();
    private static final JsonFormat.Printer PRINTER = JsonFormat.printer();

    @Test
    @DisplayName("authorized 를 안 보내면 허락 없음(false)으로 읽힌다")
    void missingAuthorizedDefaultsToDenied() throws Exception {
        LinkExtractionRequest request = parse("{\"url\": \"https://shop.example.com/p\"}");

        assertFalse(request.getAuthorized());
        assertEquals("", request.getModel());
    }

    @Test
    @DisplayName("authorized=true 를 보내면 허락으로 그대로 전달된다")
    void explicitAuthorizedIsPreserved() throws Exception {
        LinkExtractionRequest request = parse("{\"url\": \"https://shop.example.com/p\", \"authorized\": true}");

        assertTrue(request.getAuthorized());
    }

    @Test
    @DisplayName("모르는 필드는 무시된다 - 호출자가 필드를 먼저 더해도 요청이 깨지지 않는다")
    void unknownFieldIsIgnored() throws Exception {
        LinkExtractionRequest request = parse(
            "{\"url\": \"https://shop.example.com/p\", \"blockedDomains\": [\"a.example\"], \"headlessFirst\": true}");

        assertEquals("https://shop.example.com/p", request.getUrl());
    }

    @Test
    @DisplayName("모르는 enum 문자열은 UNSPECIFIED 로 읽힌다 - 상대가 값을 먼저 더해도 응답이 깨지지 않는다")
    void unknownEnumValueReadsAsUnspecified() throws Exception {
        ExtractionResult.Builder builder = ExtractionResult.newBuilder();
        PARSER.merge("{\"name\": \"x\", \"method\": \"FUTURE_METHOD\"}", builder);

        assertEquals(ExtractionMethod.EXTRACTION_METHOD_UNSPECIFIED, builder.getMethod());
        assertEquals("x", builder.getName());
    }

    @Test
    @DisplayName("안 채운 필드는 JSON 에서 생략되고 채운 필드는 계약의 이름(lowerCamelCase)으로 나간다")
    void unsetFieldsAreOmitted() throws Exception {
        ExtractionResult result = ExtractionResult.newBuilder()
            .setName("상품")
            .setCurrentPrice(0)
            .setMethod(ExtractionMethod.STRUCTURED)
            .build();

        String json = PRINTER.omittingInsignificantWhitespace().print(result);

        // optional 스칼라는 0 이어도 "채웠다"가 보존된다 — 무료 상품(0원)이 생략으로 사라지지 않는다.
        assertEquals("{\"name\":\"상품\",\"currentPrice\":0,\"method\":\"STRUCTURED\"}", json);
    }

    private static LinkExtractionRequest parse(String json) throws Exception {
        LinkExtractionRequest.Builder builder = LinkExtractionRequest.newBuilder();
        PARSER.merge(json, builder);
        return builder.build();
    }
}
