package com.depromeet.piki.extractor.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.depromeet.piki.contracts.extraction.v1.ExtractionResult;
import com.depromeet.piki.contracts.extraction.v1.ParseResult;
import com.depromeet.piki.extractor.common.storage.StoredImage;
import com.depromeet.piki.extractor.extraction.PageContent;
import com.depromeet.piki.extractor.extraction.http.PageFetchException;
import com.depromeet.piki.extractor.image.gemini.GeminiImageResult;
import com.depromeet.piki.extractor.support.IntegrationTestSupport;
import com.depromeet.piki.extractor.support.StubGeminiClient;
import com.depromeet.piki.extractor.support.StubImageStorage;
import com.depromeet.piki.extractor.support.StubPageFetcher;
import com.google.protobuf.Descriptors.FieldDescriptor;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 스트림 요청 한 건이 실제 추출 파이프라인을 거쳐 결과 항목이 되는지 검증한다. 외부 경계만 stub 이고, 스트림 자체의
 * 전달 규칙은 ParseStreamWorkerTest 가 본다.
 */
class ParseRequestProcessorIntegrationTest extends IntegrationTestSupport {

    private static final String STRUCTURED_HTML = """
        <html><head><script type="application/ld+json">
        {"@type":"Product","name":"직접파싱 상품","image":"https://cdn.example.com/p.png",
         "offers":{"price":"39000","priceCurrency":"KRW",
           "priceSpecification":{"priceType":"https://schema.org/StrikethroughPrice","price":"49000"}}}
        </script></head><body></body></html>
        """;

    @Autowired
    private ParseRequestProcessor processor;

    @Autowired
    private StubPageFetcher stubPageFetcher;

    @Autowired
    private StubGeminiClient stubGeminiClient;

    @Autowired
    private StubImageStorage stubImageStorage;

    private static Map<String, String> request(String inputType, String inputValue) {
        return Map.of("requestId", "42", "inputType", inputType, "inputValue", inputValue);
    }

    private static ExtractionPolicySnapshot policy(String json) {
        return ExtractionPolicySnapshot.parse(json);
    }

    @Test
    @DisplayName("링크 요청이 성공하면 code OK 와 추출 값을 계약의 필드 이름으로 싣는다")
    void linkSuccess() {
        stubGeminiClient.reset();
        stubPageFetcher.build = link -> PageContent.of(link, STRUCTURED_HTML);

        Map<String, String> result = processor
            .process(request("LINK", "https://shop.example.com/p/1"), ExtractionPolicySnapshot.DEFAULT, "test")
            .orElseThrow();

        assertEquals("42", result.get("requestId"));
        assertEquals("OK", result.get("code"));
        assertEquals("직접파싱 상품", result.get("name"));
        assertEquals("https://cdn.example.com/p.png", result.get("imageUrl"));
        assertEquals("39000", result.get("currentPrice"));
        assertEquals("KRW", result.get("currency"));
        assertEquals("https://shop.example.com/p/1", result.get("finalUrl"));
        assertEquals("STRUCTURED", result.get("method"));
        assertEquals("49000", result.get("originalPrice"));
        // HTTP 성공 응답의 필드가 늘었는데 스트림 결과로 옮기지 않으면 여기서 깨진다. 둘이 더 많은 것은 requestId 와 code
        assertEquals(ExtractionResult.getDescriptor().getFields().size() + 2, result.size());
        for (FieldDescriptor field : ExtractionResult.getDescriptor().getFields()) {
            assertEquals(field.getType(), ParseResult.getDescriptor().findFieldByName(field.getName()).getType(), field.getName());
        }
    }

    @Test
    @DisplayName("정책의 신원과 차단 목록이 fetch 경계까지 전달된다")
    void policyReachesFetchBoundary() {
        stubGeminiClient.reset();
        stubPageFetcher.build = link -> PageContent.of(link, STRUCTURED_HTML);

        processor.process(
            request("LINK", "https://shop.example.com/p/1"),
            policy("{\"identity\": \"PiKi/2\", \"blockedDomains\": [\"blocked.example.com\"]}"),
            "test"
        );

        assertEquals("PiKi/2", stubPageFetcher.lastIdentity.value());
        assertTrue(stubPageFetcher.lastBlockedDomains.values().contains("blocked.example.com"));
    }

    @Test
    @DisplayName("추출이 계약 실패로 끝나면 그 code 만 싣는다")
    void linkFailureCarriesCode() {
        stubGeminiClient.reset();
        stubPageFetcher.build = link -> {
            throw PageFetchException.blockedDomain();
        };

        Map<String, String> result = processor
            .process(request("LINK", "https://shop.example.com/p/1"), ExtractionPolicySnapshot.DEFAULT, "test")
            .orElseThrow();

        assertEquals(Map.of("requestId", "42", "code", "BLOCKED_DOMAIN"), result);
    }

    @Test
    @DisplayName("형식이 틀린 링크는 추출하지 않고 INVALID_URL 로 닫는다")
    void malformedLink() {
        Map<String, String> result = processor
            .process(request("LINK", "http://shop.example.com/p/1"), ExtractionPolicySnapshot.DEFAULT, "test")
            .orElseThrow();

        assertEquals(Map.of("requestId", "42", "code", "INVALID_URL"), result);
    }

    @Test
    @DisplayName("이미지 요청은 정책의 버킷과 모델로 추출한다")
    void imageSuccess() {
        stubGeminiClient.reset();
        stubImageStorage.onDownload = (bucket, key) -> {
            assertEquals("piki-dev-images", bucket);
            return new StoredImage(new byte[] {1, 2, 3}, "image/png");
        };
        stubGeminiClient.build = request -> new GeminiImageResult("나이키 신발", 89000, "신발", "KRW", null);

        Map<String, String> result = processor.process(
            request("IMAGE", "items/raw/a.png"),
            policy("{\"imageBucket\": \"piki-dev-images\", \"imageModel\": \"gemini-image\"}"),
            "test"
        ).orElseThrow();

        assertEquals("OK", result.get("code"));
        assertEquals("나이키 신발", result.get("name"));
        assertEquals(StubImageStorage.UPLOADED_URL, result.get("imageUrl"));
        assertEquals("LLM", result.get("method"));
        assertFalse(result.containsKey("finalUrl"));
        assertEquals("gemini-image", stubGeminiClient.lastModel());
    }

    @Test
    @DisplayName("정책에 버킷이 없으면 이미지 요청을 DELIVERY_EXHAUSTED 로 닫는다")
    void imageWithoutBucket() {
        Map<String, String> result = processor
            .process(request("IMAGE", "items/raw/a.png"), ExtractionPolicySnapshot.DEFAULT, "test")
            .orElseThrow();

        assertEquals(Map.of("requestId", "42", "code", "DELIVERY_EXHAUSTED"), result);
    }

    @Test
    @DisplayName("모르는 입력 종류는 DELIVERY_EXHAUSTED 로 닫는다")
    void unknownInputType() {
        Map<String, String> result = processor
            .process(request("VIDEO", "x"), ExtractionPolicySnapshot.DEFAULT, "test")
            .orElseThrow();

        assertEquals(Map.of("requestId", "42", "code", "DELIVERY_EXHAUSTED"), result);
    }

    @Test
    @DisplayName("요청 번호를 읽지 못하면 결과를 만들지 않는다")
    void unreadableRequestId() {
        assertTrue(processor.process(Map.of("inputType", "LINK"), ExtractionPolicySnapshot.DEFAULT, "test").isEmpty());
        assertTrue(processor.exhausted(Map.of("requestId", "abc"), "test").isEmpty());
    }

    @Test
    @DisplayName("배달 상한을 넘긴 요청의 결과는 DELIVERY_EXHAUSTED 다")
    void exhaustedResult() {
        Map<String, String> result = processor.exhausted(request("LINK", "https://shop.example.com/p/1"), "test").orElseThrow();

        assertEquals(Map.of("requestId", "42", "code", "DELIVERY_EXHAUSTED"), result);
    }
}
