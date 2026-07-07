package com.depromeet.piki.extractor.image.gemini;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Gemini JSON Schema 파서는 "properties": null 같은 잉여 null 필드를 스키마 위반으로 취급한다.
// (GeminiExtractionRequest 와 동일 정책) 직렬화 단계에서 null 필드를 전부 생략한다.
//
// 주의: 이미지 경로의 wire 는 link 경로(GeminiExtractionRequest)와 다르다 —
// 필드명은 responseJsonSchema 가 아니라 responseSchema, 스키마 type 은 소문자 문자열이 아니라 SchemaType enum
// (직렬화 시 "OBJECT"/"STRING"/"INTEGER" 대문자), 그리고 이미지 part 를 담는 sealed Part(Text/Image) 가 있다.
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GeminiImageRequest(
    GenerationConfig generationConfig,
    List<Content> contents
) {

    public record GenerationConfig(
        String responseMimeType,
        Schema responseSchema,
        // gemini-3.1-flash-lite 는 thinkingLevel 을 명시하지 않으면 dynamic thinking 이 붙어
        // 이미지 추출처럼 단순한 작업에도 응답이 간헐적으로 ~20s 까지 튄다 (read-timeout 초과 → 호출 실패).
        // 이미지에서 직접 읽는 작업이라 깊은 추론이 불필요하므로 minimal 로 고정해 ~3s 로 안정화한다.
        ThinkingConfig thinkingConfig
    ) {}

    public record ThinkingConfig(
        String thinkingLevel
    ) {}

    public record Content(
        List<Part> parts
    ) {}

    // 텍스트 파트와 이미지 파트가 한 리스트에 섞여 온다.
    // 이 요청은 직렬화만 되고 역직렬화되지 않으므로 타입 판별자(discriminator)가 붙지 않는다 —
    // Jackson 은 런타임 타입의 컴포넌트만 찍어 {"text":...} / {"inlineData":...} 로 나간다.
    public sealed interface Part permits Part.Text, Part.Image {

        record Text(String text) implements Part {}

        record Image(InlineData inlineData) implements Part {}
    }

    public record InlineData(
        String mimeType,
        String data
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Schema(
        SchemaType type,
        Map<String, Schema> properties,
        Schema items,
        List<String> required,
        Boolean nullable
    ) {

        // 나머지 필드를 null 로 채우는 편의 팩토리 — 타입만, 타입+nullable(true) 만 지정하는 두 형태.
        static Schema ofType(SchemaType type) {
            return new Schema(type, null, null, null, null);
        }

        static Schema ofNullableType(SchemaType type) {
            return new Schema(type, null, null, null, true);
        }
    }

    public enum SchemaType {
        OBJECT,
        ARRAY,
        STRING,
        INTEGER,
    }

    // 이미지에서 직접 정보를 읽는 작업이라 깊은 추론이 불필요하다. dynamic thinking 비활성.
    private static final String MINIMAL_THINKING = "minimal";

    private static final String SYSTEM_PROMPT = """
            You are a product information extractor. The user captured a product page to identify the product they are interested in.

            **Intent inference**: The user wants information about the MAIN product on the page. Ignore related products, recommended items, ads, and sidebar content. Focus on the primary product that occupies the central area of the page.

            Extract the following for the main product only:

            1. **name**: The product name exactly as displayed. null if not found.
            2. **price**: The price as an integer (remove currency symbols, commas). If multiple prices exist, use the final/sale price. null if not found.
            3. **category**: The category for the product (e.g. "식품", "음료", "생활용품", "의류", "전자기기", "화장품" etc.). If the category is explicitly shown on the page (e.g. breadcrumb, tag), use that text. Otherwise infer from the product. null if completely unclear.
            4. **currency**: The ISO 4217 currency code (3 letters) of the price. Map unambiguous symbols/text directly (₩/원 → KRW, ¥/円 → JPY, € → EUR). For ambiguous symbols like "$" (used by USD, CAD, AUD, SGD, etc.), infer ONLY from page context (language/country/domain); if the context is unclear, return null. null if there is no price or the currency cannot be determined confidently.
            5. **boundingBox**: The bounding box of the MAIN product's image (photo) region in the captured image, as integers normalized to 0-1000 (yMin, xMin, yMax, xMax) — (0,0) is top-left, (1000,1000) is bottom-right. Box only the product photo, not text/price/UI. null if the product photo region cannot be located.

            Return information for the single main product only. Do NOT include related/recommended/ad products.
            Handle any language (Korean, Japanese, English, etc.).\
            """;

    private static final Schema PRODUCT_SCHEMA = productSchema();

    // LinkedHashMap(삽입순 보존)으로 스키마 property 순서를 고정한다.
    private static Schema productSchema() {
        Map<String, Schema> boundingBoxProperties = new LinkedHashMap<>();
        boundingBoxProperties.put("yMin", Schema.ofNullableType(SchemaType.INTEGER));
        boundingBoxProperties.put("xMin", Schema.ofNullableType(SchemaType.INTEGER));
        boundingBoxProperties.put("yMax", Schema.ofNullableType(SchemaType.INTEGER));
        boundingBoxProperties.put("xMax", Schema.ofNullableType(SchemaType.INTEGER));
        Schema boundingBox = new Schema(SchemaType.OBJECT, boundingBoxProperties, null, null, true);

        Map<String, Schema> properties = new LinkedHashMap<>();
        properties.put("name", Schema.ofNullableType(SchemaType.STRING));
        properties.put("price", Schema.ofNullableType(SchemaType.INTEGER));
        properties.put("category", Schema.ofNullableType(SchemaType.STRING));
        properties.put("currency", Schema.ofNullableType(SchemaType.STRING));
        properties.put("boundingBox", boundingBox);
        return new Schema(SchemaType.OBJECT, properties, null, null, null);
    }

    public static GeminiImageRequest forImageAnalysis(String base64Image, String mimeType) {
        return new GeminiImageRequest(
            new GenerationConfig(
                "application/json",
                PRODUCT_SCHEMA,
                new ThinkingConfig(MINIMAL_THINKING)),
            List.of(
                new Content(
                    List.of(
                        new Part.Text(SYSTEM_PROMPT),
                        new Part.Image(new InlineData(mimeType, base64Image))))));
    }
}
