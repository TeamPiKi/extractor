package com.depromeet.piki.extractor.extraction.gemini;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gemini JSON Schema 파서는 {@code "properties": null} 같은 잉여 null 필드를 스키마 위반으로 취급한다.
 * 그래서 직렬화 단계에서 null 필드를 전부 생략한다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GeminiExtractionRequest(
    GenerationConfig generationConfig,
    List<Content> contents
) {

    public record GenerationConfig(
        String responseMimeType,
        JsonSchema responseJsonSchema
    ) {}

    public record Content(
        List<Part> parts
    ) {}

    public record Part(
        String text
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record JsonSchema(
        String type,
        String description,
        Map<String, JsonSchema> properties,
        JsonSchema items,
        List<String> required,
        Boolean nullable
    ) {

        static JsonSchema ofType(String type) {
            return new JsonSchema(type, null, null, null, null, null);
        }

        static JsonSchema ofNullableType(String type) {
            return new JsonSchema(type, null, null, null, null, true);
        }
    }

    /**
     * 서버에서 직접 fetch 한 HTML 을 in-context 로 넘기므로 Gemini 의 url_context tool 을 쓰지 않는다.
     * CSR 페이지의 inline JSON-LD 등 정적 HTML 안의 정보를 LLM 이 직접 보고 추출하도록 유도한다.
     */
    private static final String SYSTEM_PROMPT = """
            You are a product information extractor. Given the URL and the HTML of a product page,
            extract information for the MAIN product of the page.

            **Grounding rule (highest priority, applies to every field)**:
            Every value you output MUST be taken from the provided HTML. If a value is not present
            in the HTML, return null for that field (or isProductPage=false if the page shows no
            product at all). NEVER invent, guess, or recall values from memory or from similar
            products you have seen elsewhere. A fabricated value is the worst possible outcome —
            strictly worse than null.

            **Input**:
            - URL: the product page URL
            - HTML: raw HTML content of that page (may include <script type="application/ld+json"> JSON-LD,
              <meta property="og:..."> Open Graph tags, inline JSON state, and visible body text)

            **Strategy**:
            1. Prefer JSON-LD product schema (Schema.org Product) — fields like offers.price, priceSpecification.price,
               priceCurrency, name, image. This is the most reliable source.
            2. Then Open Graph meta tags (og:title, og:image, og:description) and visible price text.
            3. If no reliable price source exists, return null for unknown fields. DO NOT GUESS.

            Ignore related products, recommended items, advertisements, and sidebar content.

            **Fields**:
            1. isProductPage (boolean, required): true if the page describes a single identifiable product for sale.
               false if this is a list/search/category page, an article, or non-commerce content.
            2. name (string): The product name exactly as displayed. null if isProductPage is false.
            3. currentPrice (integer): The price a buyer would pay RIGHT NOW. Remove currency symbols, commas, decimals.
               If a discounted/sale price is shown, this is the discounted price. If only a single price is shown,
               this is that price. NEVER report the strikethrough/original/pre-discount price here.
            4. currency (string): ISO 4217 code (KRW, USD, JPY, EUR, etc.). Infer from page language/locale if ambiguous.
            5. imageUrl (string): ABSOLUTE URL of the primary product image. Prefer og:image meta tag,
               fallback to the main product image. Resolve relative URLs against the page URL.
               The URL (or the relative path you resolve) must appear in the HTML — do not construct
               or abbreviate image URLs. If no product image URL appears in the HTML, return null.

            **Price rules**:
            - Single price, no discount indicator → that price is currentPrice.
            - Both original (strikethrough) and sale prices visible → currentPrice = sale price.
            - JSON-LD with offers.price → that is currentPrice. Ignore priceSpecification entries marked as
              StrikethroughPrice / ListPrice / original.

            Respond with JSON only, matching the provided schema. Handle any language.\
            """;

    private static final JsonSchema EXTRACTION_SCHEMA = extractionSchema();

    private static JsonSchema extractionSchema() {
        Map<String, JsonSchema> properties = new LinkedHashMap<>();
        properties.put("isProductPage", JsonSchema.ofType("boolean"));
        properties.put("name", JsonSchema.ofNullableType("string"));
        properties.put("currentPrice", JsonSchema.ofNullableType("integer"));
        properties.put("currency", JsonSchema.ofNullableType("string"));
        properties.put("imageUrl", JsonSchema.ofNullableType("string"));
        return new JsonSchema("object", null, properties, null, List.of("isProductPage"), null);
    }

    public static GeminiExtractionRequest forHtmlExtraction(URI url, String html) {
        return new GeminiExtractionRequest(
            new GenerationConfig("application/json", EXTRACTION_SCHEMA),
            List.of(
                new Content(
                    List.of(
                        new Part(SYSTEM_PROMPT),
                        new Part("URL: " + url + "\n\nHTML:\n" + html)
                    )
                )
            )
        );
    }
}
