package com.depromeet.piki.extractor.extraction.structured;

import com.depromeet.piki.extractor.domain.ProductLink;
import com.depromeet.piki.extractor.domain.ProductSnapshot;
import com.depromeet.piki.extractor.domain.ProductSnapshotException;
import com.depromeet.piki.extractor.extraction.PageContent;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// PIKI-Server: product/service/structured/StructuredDataExtractor.kt 포팅.
// fetch 된 HTML 의 구조화 데이터(JSON-LD schema.org/Product · OpenGraph)를 코드로 파싱해
// LLM 호출 없이 ProductSnapshot 을 만든다. 필수 필드(name+currentPrice)가 검증을 통과하면 Extracted,
// 미달·부재·검증위반이면 사유를 담은 Miss 를 돌려 오케스트레이터가 Gemini fallback 으로 넘어가게 한다.
//
// jsoup 은 마크업에서 <script ld+json>·<meta og:*> 블록을 정확히 꺼내는 책임만 지고, JSON-LD 값 자체는
// Jackson 3 트리(JsonNode)로 다룬다. 깨진 ld+json 한 덩어리가 전체를 죽이지 않도록 각 script 를 격리해 파싱한다.
@Component
public class StructuredDataExtractor {

    // 통화기호·천단위 콤마·공백 등 숫자/소수점/부호 외 문자를 제거. 맨 앞 '-'(음수)는 살려 음수 배제가 동작하게 둔다.
    private static final Pattern PRICE_NOISE = Pattern.compile("[^0-9.\\-]");

    private final ObjectMapper objectMapper;

    public StructuredDataExtractor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // 오케스트레이터가 파싱한 Document 를 공유받아 읽기만 한다(Document 를 변형하지 않으므로 이후 Gemini fallback 과 안전하게 공유).
    // 우선순위: JSON-LD(구조적 가격을 정확히 들고 있음) > OpenGraph(가격 표준 태그가 없어 보조).
    // 둘 다 실패하면 더 데이터에 근접했던 사유(worse)를 reason 으로 보고한다.
    public StructuredExtraction extract(Document document, ProductLink link) {
        StructuredExtraction fromJsonLd = fromJsonLd(document, link);
        if (fromJsonLd instanceof StructuredExtraction.Extracted) {
            return fromJsonLd;
        }
        StructuredExtraction fromOpenGraph = fromOpenGraph(document, link);
        if (fromOpenGraph instanceof StructuredExtraction.Extracted) {
            return fromOpenGraph;
        }
        // 둘 다 Miss — 데이터에 더 근접한 사유를 고른다.
        return worse((StructuredExtraction.Miss) fromJsonLd, (StructuredExtraction.Miss) fromOpenGraph);
    }

    // 단독 호출·테스트 편의: HTML 을 직접 파싱해 위임한다. 운영 경로는 오케스트레이터가 Document 를 만들어 공유한다.
    // baseUri 는 html 의 출처인 최종 URL(finalUrl) 기준, 정체성으로 넘기는 link 는 원본 유지.
    public StructuredExtraction extract(PageContent page) {
        Document document = Jsoup.parse(page.html(), page.finalUrl().value().toString());
        return extract(document, page.link());
    }

    // 두 실패 사유 중 데이터에 더 근접한(=정보량이 큰) 쪽. enum 선언 순서가 아니라 명시 rank 로 비교한다(선언 순서 변경에 독립).
    private StructuredExtraction.Miss worse(StructuredExtraction.Miss a, StructuredExtraction.Miss b) {
        return a.rank() >= b.rank() ? a : b;
    }

    // --- JSON-LD (schema.org/Product) ---

    private StructuredExtraction fromJsonLd(Document document, ProductLink link) {
        List<JsonNode> products = new ArrayList<>();
        for (Element script : document.select("script[type]")) {
            // type 속성에 charset 파라미터·따옴표·공백 변형이 붙어도 application/ld+json 으로 인식한다(jsoup 이 파싱한 attr 기준).
            if (!script.attr("type").trim().toLowerCase(Locale.ROOT).startsWith("application/ld+json")) {
                continue;
            }
            JsonNode root = readTreeOrNull(script.data());
            if (root == null) {
                continue;
            }
            products.addAll(collectProductNodes(root));
        }
        // Product 노드가 하나도 없으면 구조화 데이터 부재.
        if (products.isEmpty()) {
            return StructuredExtraction.Miss.NO_DATA;
        }
        // 시드는 최저 rank(NO_DATA)에서 시작해 노드 결과로 올린다. 노드가 있으므로 toSnapshotFromProduct 는
        // 항상 MISSING_FIELD 이상(또는 Extracted)을 주어, 첫 반복에서 곧바로 실제 사유로 덮인다.
        StructuredExtraction.Miss worst = StructuredExtraction.Miss.NO_DATA;
        // 앞 Product 가 검증에 실패해도(요약용 불완전 노드 등) 뒤의 완전한 Product 까지 모두 시도한다.
        for (JsonNode product : products) {
            StructuredExtraction result = toSnapshotFromProduct(product, link);
            if (result instanceof StructuredExtraction.Extracted) {
                return result;
            }
            worst = worse(worst, (StructuredExtraction.Miss) result);
        }
        return worst;
    }

    private StructuredExtraction toSnapshotFromProduct(JsonNode product, ProductLink link) {
        JsonNode offer = firstOffer(product);
        return toResult(
            link,
            textOf(product.get("name")),
            imageUrlOf(product),
            textOf(priceNode(offer)),
            textOf(offer == null ? null : offer.get("priceCurrency"))
        );
    }

    // 최상위 배열 / @graph 래핑 / ItemList.itemListElement[].item 중첩을 재귀로 평탄화해 모든 Product 노드를 모은다.
    // 첫 후보가 불완전해도 뒤 후보로 넘어갈 수 있게 전부 수집한다. JsonNode 순회는 안전한 인덱스 접근(size/get)으로.
    private List<JsonNode> collectProductNodes(JsonNode node) {
        List<JsonNode> products = new ArrayList<>();
        collectProductNodesInto(node, products);
        return products;
    }

    private void collectProductNodesInto(JsonNode node, List<JsonNode> acc) {
        if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                collectProductNodesInto(node.get(i), acc);
            }
            return;
        }
        JsonNode graph = node.get("@graph");
        if (graph != null) {
            collectProductNodesInto(graph, acc);
        }
        JsonNode itemList = node.get("itemListElement");
        if (itemList != null) {
            for (int i = 0; i < itemList.size(); i++) {
                JsonNode item = itemList.get(i).get("item");
                if (item != null) {
                    collectProductNodesInto(item, acc);
                }
            }
        }
        if (isProductType(node)) {
            acc.add(node);
        }
    }

    private boolean isProductType(JsonNode node) {
        JsonNode type = node.get("@type");
        if (type == null) {
            return false;
        }
        if (type.isArray()) {
            for (int i = 0; i < type.size(); i++) {
                if (isProductTypeValue(type.get(i))) {
                    return true;
                }
            }
            return false;
        }
        return isProductTypeValue(type);
    }

    private boolean isProductTypeValue(JsonNode node) {
        String text = textOf(node);
        return text != null && text.equalsIgnoreCase("Product");
    }

    // offers 는 객체 또는 배열(AggregateOffer 의 offers 배열 등). 배열이면 첫 원소를 쓴다.
    private JsonNode firstOffer(JsonNode product) {
        JsonNode offers = product.get("offers");
        if (offers == null) {
            return null;
        }
        if (offers.isArray()) {
            return offers.size() > 0 ? offers.get(0) : null;
        }
        return offers;
    }

    // price 우선순위: offers.price → offers.priceSpecification.price → AggregateOffer.lowPrice.
    private JsonNode priceNode(JsonNode offer) {
        if (offer == null) {
            return null;
        }
        JsonNode price = offer.get("price");
        if (price != null) {
            return price;
        }
        JsonNode priceSpecification = offer.get("priceSpecification");
        if (priceSpecification != null) {
            JsonNode specPrice = priceSpecification.get("price");
            if (specPrice != null) {
                return specPrice;
            }
        }
        JsonNode lowPrice = offer.get("lowPrice");
        if (lowPrice != null) {
            return lowPrice;
        }
        return null;
    }

    private String imageUrlOf(JsonNode product) {
        JsonNode image = product.get("image");
        if (image == null) {
            return null;
        }
        return firstImageUrl(image);
    }

    // image 는 문자열 URL · ImageObject(url·contentUrl) · 그 배열 중 하나다. 첫 유효 URL 을 뽑는다.
    // schema.org ImageObject 는 url 또는 contentUrl 로 실제 주소를 담으므로(29cm 는 contentUrl) 둘 다 본다.
    private String firstImageUrl(JsonNode node) {
        if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                String url = firstImageUrl(node.get(i));
                if (url != null) {
                    return url;
                }
            }
            return null;
        }
        if (node.isObject()) {
            String url = textOf(node.get("url"));
            return url != null ? url : textOf(node.get("contentUrl"));
        }
        return textOf(node);
    }

    // --- OpenGraph (JSON-LD 가 실패했을 때의 보조 경로) ---

    private StructuredExtraction fromOpenGraph(Document document, ProductLink link) {
        String name = stripSiteSuffix(metaContent(document, "og:title"), metaContent(document, "og:site_name"));
        String imageUrl = metaContent(document, "og:image");
        // 가격은 OG 표준(product:price:amount) 우선, 없으면 embedded JS state 에서 보강한다(resolvePrice).
        ResolvedPrice resolved = resolvePrice(document);
        String priceText = resolved.price();
        String currency = resolved.currency();
        // OG 관련 태그(title·price·image·currency)가 하나도 없으면 구조화 데이터 부재. 하나라도 있으면 toResult 가 missing/invalid 를 가린다.
        // currency 도 포함해, 통화 태그만 단독으로 있는 페이지가 no_data 로 오분류되지 않게 한다(부분 제공 → missing_field).
        if (name == null && priceText == null && imageUrl == null && currency == null) {
            return StructuredExtraction.Miss.NO_DATA;
        }
        return toResult(link, name, imageUrl, priceText, currency);
    }

    // 가격·통화 해석 결과. resolvePrice 는 price 가 null 일 수 있고, priceFromEmbeddedState 는 price 가 채워졌을 때만 반환한다.
    private record ResolvedPrice(String price, String currency) {
    }

    // 가격·통화 해석. OG 표준 가격 태그(product:price:amount)가 있으면 그대로, 없으면 embedded JS state 에서 보강한다.
    // OG 가 이름·이미지는 주지만 가격을 JS state(window.__PRELOADED_STATE__ 등)에만 둔 SPA(예: 유니클로)를 LLM 없이
    // 추출하기 위한 특화 경로다 — 가격이 거대 state 깊숙이 있어 Gemini fallback 의 토큰 상한에 안 맞는 사이트를 파서가 직접 건진다.
    // OG 표준이 있으면 그대로 써 불필요한 state 파싱을 피한다.
    private ResolvedPrice resolvePrice(Document document) {
        // currency(product:price:currency)는 amount 유무와 독립으로 읽는다 — 통화 태그만 단독으로 있는 페이지가
        // no_data 로 오분류되지 않게(부분 제공 → missing_field). amount 가 있으면 그대로, 없으면 embedded state 로 보강.
        String ogCurrency = metaContent(document, "product:price:currency");
        String ogAmount = metaContent(document, "product:price:amount");
        if (ogAmount != null) {
            return new ResolvedPrice(ogAmount, ogCurrency);
        }
        ResolvedPrice embedded = priceFromEmbeddedState(document);
        if (embedded == null) {
            return new ResolvedPrice(null, ogCurrency);
        }
        String currency = embedded.currency() != null ? embedded.currency() : ogCurrency;
        return new ResolvedPrice(embedded.price(), currency);
    }

    // embedded JS state 의 JSON 에서 (가격, 통화)를 찾는다. 유니클로식 가격 컨테이너
    // "prices":{"base":{"value":N,"currency":{"code":C}}} 를 트리에서 탐색한다.
    private ResolvedPrice priceFromEmbeddedState(Document document) {
        JsonNode state = embeddedStateJson(document);
        if (state == null) {
            return null;
        }
        JsonNode prices = findPricesNode(state);
        if (prices == null) {
            return null;
        }
        JsonNode base = prices.path("base");
        JsonNode valueNode = base.path("value");
        if (!valueNode.isNumber()) {
            return null;
        }
        String value = valueNode.asString();
        JsonNode codeNode = base.path("currency").path("code");
        String currency = codeNode.isString() ? codeNode.asString() : null;
        return new ResolvedPrice(value, currency);
    }

    // 두 형태의 embedded state 를 JsonNode 로 읽는다: <script id="__NEXT_DATA__" type="application/json"> 의 순수 JSON,
    // 또는 window.__PRELOADED_STATE__ = {...} JS 할당. 후자는 할당 뒤에 코드가 붙을 수 있어 균형 중괄호로 객체만 떼낸다.
    private JsonNode embeddedStateJson(Document document) {
        Element nextData = document.selectFirst("script#__NEXT_DATA__");
        if (nextData != null) {
            JsonNode parsed = readTreeOrNull(nextData.data());
            if (parsed != null) {
                return parsed;
            }
        }
        Element script = null;
        for (Element candidate : document.select("script")) {
            if (candidate.data().contains("__PRELOADED_STATE__")) {
                script = candidate;
                break;
            }
        }
        if (script == null) {
            return null;
        }
        String raw = script.data();
        int start = raw.indexOf('{', raw.indexOf("__PRELOADED_STATE__"));
        if (start < 0) {
            return null;
        }
        String json = extractBalancedJson(raw, start);
        if (json == null) {
            return null;
        }
        return readTreeOrNull(json);
    }

    // '{' 부터 문자열 리터럴·이스케이프를 고려해 짝이 맞는 '}' 까지 잘라낸다(JS 할당 뒤 trailing 코드 제거).
    private String extractBalancedJson(String text, int start) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escaped) {
                escaped = false;
            } else if (inString && c == '\\') {
                escaped = true;
            } else if (c == '"') {
                inString = !inString;
            } else if (!inString && c == '{') {
                depth++;
            } else if (!inString && c == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    // JSON 트리를 재귀로 훑어 "prices":{"base":{"value":number}} 형태의 prices 노드를 찾는다(유니클로 상품 가격 컨테이너).
    // JsonNode 는 자식(object 값·array 원소)에 대한 Iterable 이라 자식을 직접 순회한다. value 노드는 자식이 없어 멈춘다.
    private JsonNode findPricesNode(JsonNode node) {
        if (node.path("prices").path("base").path("value").isNumber()) {
            return node.path("prices");
        }
        for (JsonNode child : node) {
            JsonNode found = findPricesNode(child);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private String metaContent(Document document, String property) {
        Element meta = document.selectFirst("meta[property=" + property + "]");
        if (meta == null) {
            return null;
        }
        String content = meta.attr("content");
        return content.isBlank() ? null : content;
    }

    // og:title 끝의 사이트명 꼬리표(" | 무신사" 등)를 제거한다. og:title 은 페이지 제목이라 사이트명이 붙기 쉬운데,
    // 그게 상품명으로 새지 않도록 og:site_name 과 일치하는 접미만 떼어낸다(없거나 안 맞으면 원본 유지 — host 무관 일반 규칙).
    private String stripSiteSuffix(String title, String siteName) {
        if (title == null) {
            return null;
        }
        if (siteName == null) {
            return title;
        }
        String site = siteName.trim();
        if (site.isBlank()) {
            return title;
        }
        // 구분자(" | "·" - ") + site_name 으로 시작하는 꼬리표를 떼낸다. 정확 일치(끝이 딱 site_name)뿐 아니라
        // site_name 뒤에 국가·언어 코드가 붙은 경우(유니클로 "상품명 | UNIQLO KR", site_name "UNIQLO")도 잘라낸다.
        // 여러 번 나오면 마지막 위치 기준(상품명 본문의 구분자는 보존). 없거나 안 맞으면 원본 유지(host 무관 일반 규칙).
        for (String separator : List.of(" | ", " - ")) {
            String marker = separator + site;
            int idx = title.lastIndexOf(marker);
            if (idx >= 0) {
                return title.substring(0, idx).trim();
            }
        }
        return title;
    }

    // --- 공통 ---

    // 필수 필드(name+price)가 모두 있고 정규화·범위검증을 통과하면 Extracted.
    //   name·priceText 부재 → MISSING_FIELD (가격은 raw 텍스트 유무로 판단해, 값이 있으나 못 뽑은 경우와 구분한다)
    //   price 파싱 불가(음수·범위초과·비숫자) → INVALID_VALUE
    //   fromExtracted 범위 위반(name 길이초과 등) → INVALID_VALUE
    //   정규화 후 name 이 blank→null (OG site suffix 제거로 빈 문자열이 된 경우 등) → MISSING_FIELD
    private StructuredExtraction toResult(ProductLink link, String name, String imageUrl, String priceText, String currency) {
        if (name == null) {
            return StructuredExtraction.Miss.MISSING_FIELD;
        }
        if (priceText == null) {
            return StructuredExtraction.Miss.MISSING_FIELD;
        }
        Integer price = parsePrice(priceText);
        if (price == null) {
            return StructuredExtraction.Miss.INVALID_VALUE;
        }
        ProductSnapshot snapshot;
        try {
            snapshot = ProductSnapshot.fromExtracted(link, name, imageUrl, price, currency);
        } catch (ProductSnapshotException e) {
            // 구조화 경로는 fromExtracted 의 범위 위반을 흡수해 INVALID_VALUE(→LLM fallback)로 보고한다
            // (LLM 경로는 같은 예외를 그대로 흘려 확정 실패로 떨어뜨린다 — 같은 검증, 실패 표현만 다름).
            return StructuredExtraction.Miss.INVALID_VALUE;
        }
        if (snapshot.name() == null) {
            return StructuredExtraction.Miss.MISSING_FIELD;
        }
        return new StructuredExtraction.Extracted(snapshot);
    }

    private String textOf(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (!node.isString() && !node.isNumber()) {
            return null;
        }
        String text = node.asString();
        return text.isBlank() ? null : text;
    }

    // "39,000" · "39000.00" · "₩39,000" · 숫자형(textOf 로 문자열화) → 39000. 음수·정수화 불가는 null.
    private Integer parsePrice(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String cleaned = PRICE_NOISE.matcher(raw).replaceAll("");
        BigDecimal decimal;
        try {
            decimal = new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            return null;
        }
        int value;
        try {
            // Int 범위를 넘으면 하위 비트로 wrap 해 이상값이 통과할 수 있다. 소수는 버리고(DOWN),
            // 범위를 벗어나면 intValueExact 가 예외를 던지게 해 null 로 거른다.
            value = decimal.setScale(0, RoundingMode.DOWN).intValueExact();
        } catch (ArithmeticException e) {
            return null;
        }
        return value >= 0 ? value : null;
    }

    // 깨진 ld+json 한 덩어리가 전체를 죽이지 않도록 파싱을 격리한다(원본 runCatching 대응).
    private JsonNode readTreeOrNull(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JacksonException e) {
            return null;
        }
    }
}
