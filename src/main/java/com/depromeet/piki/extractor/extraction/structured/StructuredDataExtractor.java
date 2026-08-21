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
import lombok.RequiredArgsConstructor;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * fetch 된 HTML 의 구조화 데이터(JSON-LD schema.org/Product · OpenGraph)를 코드로 파싱해 LLM 호출 없이
 * ProductSnapshot 을 만든다. 미달·부재·검증위반이면 사유를 담은 Miss 를 돌려 오케스트레이터가
 * Gemini fallback 으로 넘어가게 한다.
 *
 * <p>jsoup 은 마크업에서 {@code <script ld+json>}·{@code <meta og:*>} 블록을 정확히 꺼내는 책임만 지고,
 * JSON-LD 값 자체는 Jackson 트리(JsonNode)로 다룬다.
 */
@RequiredArgsConstructor
@Component
public class StructuredDataExtractor {

    /**
     * Next.js 가 embedded state 를 싣는 script id. 수신 가지치기({@code PruningHtmlParser})가 이 script 를
     * 남겨야 여기까지 닿으므로, 무엇을 찾는지 아는 이쪽이 상수를 소유하고 가지치기가 가져다 쓴다.
     */
    public static final String NEXT_DATA_ID = "__NEXT_DATA__";

    /** {@code window.__PRELOADED_STATE__ = {...}} JS 할당의 표지. 위와 같은 이유로 공개한다. */
    public static final String EMBEDDED_STATE_MARKER = "__PRELOADED_STATE__";

    /**
     * 통화기호·천단위 콤마·공백 등 숫자·소수점·부호 외 문자를 제거한다. 부호를 남기는 것은
     * 음수 가격이 parsePrice 에서 걸러지도록 하기 위함이다(제거하면 음수가 양수로 통과한다).
     */
    private static final Pattern PRICE_NOISE = Pattern.compile("[^0-9.\\-]");

    private final ObjectMapper objectMapper;

    /**
     * 오케스트레이터가 파싱한 Document 를 공유받아 읽기만 한다 — Document 를 변형하지 않으므로 이후
     * Gemini fallback 과 안전하게 공유된다.
     *
     * <p>JSON-LD 를 먼저 보는 이유는 구조적 가격을 정확히 들고 있기 때문이고, OpenGraph 는 가격 표준 태그가
     * 없어 보조에 그친다.
     */
    public StructuredExtraction extract(Document document, ProductLink link) {
        StructuredExtraction fromJsonLd = fromJsonLd(document, link);
        if (fromJsonLd instanceof StructuredExtraction.Extracted) {
            return fromJsonLd;
        }
        StructuredExtraction fromOpenGraph = fromOpenGraph(document, link);
        if (fromOpenGraph instanceof StructuredExtraction.Extracted) {
            return fromOpenGraph;
        }
        return worse((StructuredExtraction.Miss) fromJsonLd, (StructuredExtraction.Miss) fromOpenGraph);
    }

    /**
     * 단독 호출·테스트 편의 오버로드(운영 경로는 오케스트레이터가 만든 Document 를 공유받는다).
     * baseUri 는 수신 단계에서 이미 최종 URL 기준으로 박혀 있고, 정체성으로 넘기는 link 는 원본을 유지한다.
     */
    public StructuredExtraction extract(PageContent page) {
        return extract(page.document(), page.link());
    }

    /** 이름과 달리 "데이터에 더 근접한(=정보량이 큰) 쪽"을 고른다 — 보강 여지를 알려주는 사유가 남아야 하기 때문이다. */
    private StructuredExtraction.Miss worse(StructuredExtraction.Miss a, StructuredExtraction.Miss b) {
        return a.rank() >= b.rank() ? a : b;
    }

    // --- JSON-LD (schema.org/Product) ---

    private StructuredExtraction fromJsonLd(Document document, ProductLink link) {
        List<JsonNode> products = new ArrayList<>();
        for (Element script : document.select("script[type]")) {
            // type 에 charset 파라미터·공백 변형이 붙어도 ld+json 으로 인식하도록 startsWith 로 본다.
            if (!script.attr("type").trim().toLowerCase(Locale.ROOT).startsWith("application/ld+json")) {
                continue;
            }
            JsonNode root = readTreeOrNull(script.data());
            if (root == null) {
                continue;
            }
            products.addAll(collectProductNodes(root));
        }
        if (products.isEmpty()) {
            return StructuredExtraction.Miss.NO_DATA;
        }
        // 시드는 최저 rank 지만, 노드가 있으면 toSnapshotFromProduct 가 MISSING_FIELD 이상을 주므로
        // 첫 반복에서 실제 사유로 덮인다 — NO_DATA 가 그대로 반환되는 경로는 없다.
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

    /**
     * 최상위 배열 · {@code @graph} 래핑 · {@code ItemList.itemListElement[].item} 중첩을 재귀로 평탄화해
     * 모든 Product 노드를 모은다 — 사이트마다 Product 를 감싸는 형태가 달라 한 형태만 보면 놓친다.
     */
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

    /** offers 는 객체 또는 배열(AggregateOffer 의 offers 배열 등)로 오므로 둘 다 받는다. */
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

    /**
     * image 는 문자열 URL · ImageObject · 그 배열 중 하나로 온다. schema.org ImageObject 는 url 또는
     * contentUrl 로 실제 주소를 담으므로(29cm 는 contentUrl) 둘 다 본다.
     */
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
        ResolvedPrice resolved = resolvePrice(document);
        String priceText = resolved.price();
        String currency = resolved.currency();
        // currency 까지 봐야 통화 태그만 단독으로 있는 페이지가 no_data 로 오분류되지 않는다
        // (부분 제공은 missing_field 로 남아야 파서 보강 지점이 보인다).
        if (name == null && priceText == null && imageUrl == null && currency == null) {
            return StructuredExtraction.Miss.NO_DATA;
        }
        return toResult(link, name, imageUrl, priceText, currency);
    }

    /** 가격·통화 해석 결과. 통화만 확인된 경우가 있어 price 는 null 일 수 있다. */
    private record ResolvedPrice(String price, String currency) {
    }

    /**
     * OG 가 이름·이미지는 주지만 가격을 JS state(window.__PRELOADED_STATE__ 등)에만 둔 SPA(예: 유니클로)를
     * LLM 없이 추출하기 위한 특화 경로다 — 가격이 거대 state 깊숙이 있어 Gemini fallback 의 토큰 상한에
     * 안 맞는 사이트를 파서가 직접 건진다. OG 표준 가격 태그가 있으면 불필요한 state 파싱을 하지 않는다.
     */
    private ResolvedPrice resolvePrice(Document document) {
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

    /**
     * embedded JS state 의 JSON 에서 (가격, 통화)를 찾는다. 유니클로식 가격 컨테이너
     * {@code "prices":{"base":{"value":N,"currency":{"code":C}}}} 형태를 전제로 한다.
     */
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

    /**
     * 두 형태의 embedded state 를 읽는다: {@code <script id="__NEXT_DATA__" type="application/json">} 의 순수
     * JSON, 또는 {@code window.__PRELOADED_STATE__ = {...}} JS 할당. 후자는 할당 뒤에 코드가 붙을 수 있어
     * 균형 중괄호로 객체만 떼낸다.
     */
    private JsonNode embeddedStateJson(Document document) {
        Element nextData = document.selectFirst("script#" + NEXT_DATA_ID);
        if (nextData != null) {
            JsonNode parsed = readTreeOrNull(nextData.data());
            if (parsed != null) {
                return parsed;
            }
        }
        Element script = null;
        for (Element candidate : document.select("script")) {
            if (candidate.data().contains(EMBEDDED_STATE_MARKER)) {
                script = candidate;
                break;
            }
        }
        if (script == null) {
            return null;
        }
        String raw = script.data();
        int start = raw.indexOf('{', raw.indexOf(EMBEDDED_STATE_MARKER));
        if (start < 0) {
            return null;
        }
        String json = extractBalancedJson(raw, start);
        if (json == null) {
            return null;
        }
        return readTreeOrNull(json);
    }

    /** start 의 여는 중괄호와 짝이 맞는 곳까지 잘라낸다 — 문자열 리터럴·이스케이프 안의 중괄호는 세지 않는다. */
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

    /**
     * 가격 컨테이너가 state 어디에 묻혀 있는지 사이트마다 달라, 트리 전체를 재귀로 훑는다.
     * JsonNode 는 자식(object 값·array 원소)에 대한 Iterable 이라 자식을 직접 순회한다.
     */
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

    /**
     * og:title 은 페이지 제목이라 사이트명 꼬리표(" | 무신사" 등)가 붙기 쉽다. 그게 상품명으로 새지 않도록
     * og:site_name 과 일치하는 접미만 떼어낸다 — 없거나 안 맞으면 원본을 유지하는 host 무관 일반 규칙이다.
     */
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
        // 꼬리표 시작 위치부터 끝까지 자르므로, site_name 뒤에 국가·언어 코드가 붙은 경우도 떨어진다
        // (유니클로 "상품명 | UNIQLO KR" + site_name "UNIQLO").
        // lastIndexOf 인 것은 상품명 본문에 들어 있는 같은 구분자를 보존하기 위해서다.
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

    /**
     * 필수 필드(name+price)가 모두 있고 정규화·범위검증을 통과하면 Extracted.
     *
     * <p>가격 부재는 파싱 전 raw 텍스트 유무로 판단해, "값이 있으나 해석 못 한" 경우(INVALID_VALUE)와 구분한다.
     * fromExtracted 를 통과한 뒤 name 이 null 이면 정규화 결과가 blank 였다는 뜻이라(OG 사이트명 꼬리표 제거로
     * 빈 문자열이 된 경우 등) MISSING_FIELD 로 되돌린다.
     */
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

    /** "39,000" · "39000.00" · "₩39,000" 같은 표기를 모두 39000 으로. 음수·정수화 불가는 null. */
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
            // intValue 는 int 범위를 넘으면 하위 비트로 wrap 해 이상값을 통과시키므로 intValueExact 로 던지게 한다.
            value = decimal.setScale(0, RoundingMode.DOWN).intValueExact();
        } catch (ArithmeticException e) {
            return null;
        }
        return value >= 0 ? value : null;
    }

    /** 깨진 ld+json 한 덩어리가 페이지 전체 파싱을 죽이지 않도록 script 단위로 격리한다. */
    private JsonNode readTreeOrNull(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JacksonException e) {
            return null;
        }
    }
}
