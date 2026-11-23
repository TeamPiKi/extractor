package com.depromeet.piki.extractor.extraction.rule;

import com.depromeet.piki.contracts.extraction.v1.CssSource;
import com.depromeet.piki.contracts.extraction.v1.ExtractionRule;
import com.depromeet.piki.contracts.extraction.v1.FieldSource;
import com.depromeet.piki.contracts.extraction.v1.JsonSource;
import com.depromeet.piki.extractor.domain.ProductLink;
import com.depromeet.piki.extractor.extraction.rule.RuleReading.Field;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jsoup.internal.StringUtil;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Collector;
import org.jsoup.select.Evaluator;
import org.jsoup.select.QueryParser;
import org.jsoup.select.Selector;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonPointer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 한 몰 또는 한 쇼핑몰 제작 서비스의 페이지를 읽는 규칙. 모양과 동작 규칙의 정본은 TeamPiKi/infra 의
 * contracts/extraction.proto 와 extraction-api.md 7절이다. 선택자는 만들 때 한 번만 해석한다.
 */
public final class MallRule {

    private static final Pattern NUMBER = Pattern.compile("\\d[\\d,]*(?:\\.\\d+)?%?");
    private static final Pattern AMOUNT = Pattern.compile("\\d+|\\d{1,3}(?:,\\d{3})+");
    /** 깊이 중첩된 선택자는 해석 단계에서 스택을 넘긴다. 사람이 쓰는 선택자는 이 길이를 넘지 않는다. */
    private static final int MAX_EXPRESSION_LENGTH = 500;
    private static final String HTTPS = "https://";

    private sealed interface Source permits Css, Json {
    }

    private record Css(Evaluator selector, String attribute) implements Source {
    }

    private record Json(Evaluator script, JsonPointer pointer) implements Source {
    }

    private final long id;
    private final String host;
    private final Evaluator marker;
    private final boolean render;
    private final Source name;
    private final Source imageUrl;
    private final Source currentPrice;
    private final Source originalPrice;
    private final boolean noDiscountWhenAbsent;

    private MallRule(ExtractionRule rule) {
        this.id = rule.getId();
        this.host = rule.getTargetCase() == ExtractionRule.TargetCase.HOST ? normalizedHost(rule.getHost()) : null;
        this.marker = rule.getTargetCase() == ExtractionRule.TargetCase.MARKER
            ? selector(rule.getMarker(), "marker")
            : null;
        if (host == null && marker == null) {
            throw new InvalidRuleException("대상(host·marker)이 없다");
        }
        this.render = rule.getFetch() == ExtractionRule.FetchMode.RENDER;
        this.name = rule.hasName() ? source(rule.getName(), "name") : null;
        this.imageUrl = rule.hasImageUrl() ? source(rule.getImageUrl(), "imageUrl") : null;
        this.currentPrice = rule.hasCurrentPrice() ? source(rule.getCurrentPrice(), "currentPrice") : null;
        this.originalPrice = rule.hasOriginalPrice() ? source(rule.getOriginalPrice(), "originalPrice") : null;
        this.noDiscountWhenAbsent =
            rule.getAbsentOriginalPrice() == ExtractionRule.AbsentOriginalPrice.NO_DISCOUNT;
    }

    public static MallRule compile(ExtractionRule rule) {
        return new MallRule(rule);
    }

    public long id() {
        return id;
    }

    public boolean render() {
        return render;
    }

    boolean noDiscountWhenAbsent() {
        return noDiscountWhenAbsent;
    }

    /** host 규칙이 먼저고 그 안에서는 host 가 긴 것, 그다음 marker 규칙. 값이 작을수록 먼저다. */
    int precedence() {
        return host == null ? 1 : -host.length();
    }

    /** 대조 규칙은 요청의 blocked_domains 와 같다({@code BlockedDomains.blocks}). */
    public boolean matchesHost(ProductLink link) {
        if (host == null) {
            return false;
        }
        String target = link.value().getHost().toLowerCase(Locale.ROOT).replaceAll("\\.+$", "");
        return target.equals(host) || target.endsWith("." + host);
    }

    boolean matchesMarker(Document document) {
        return marker != null && Collector.findFirst(marker, document) != null;
    }

    public boolean matches(ProductLink finalUrl, Document document) {
        return matchesHost(finalUrl) || matchesMarker(document);
    }

    public RuleReading read(Document document, ObjectMapper objectMapper) {
        String baseUri = document.baseUri();
        // 네 필드가 같은 스크립트를 가리키는 것이 보통이라 한 번만 파싱한다
        Map<Element, Optional<JsonNode>> parsedScripts = new IdentityHashMap<>();
        Function<Element, Optional<JsonNode>> jsonOf = script ->
            parsedScripts.computeIfAbsent(script, element -> parseJson(element, objectMapper));
        return new RuleReading(
            read(name, document, jsonOf, element -> textOf(element.text().strip()), MallRule::textOf, MallRule::textOf),
            read(imageUrl, document, jsonOf, element -> textOf(element.text().strip()),
                text -> imageOf(text, baseUri), node -> imageOf(node, baseUri)),
            read(currentPrice, document, jsonOf, MallRule::spacedText, MallRule::amountOf, MallRule::amountOf),
            read(originalPrice, document, jsonOf, MallRule::spacedText, MallRule::amountOf, MallRule::amountOf)
        );
    }

    private static Optional<JsonNode> parseJson(Element script, ObjectMapper objectMapper) {
        try {
            JsonNode root = objectMapper.readTree(script.data());
            return root == null || root.isMissingNode() ? Optional.empty() : Optional.of(root);
        } catch (JacksonException e) {
            return Optional.empty();
        }
    }

    /**
     * 가격은 글자 조각 사이에 공백을 둔다. 붙여 읽으면 취소선 정가와 판매가, 할인율과 판매가가 한 숫자로 합쳐진다
     * ({@code <em>30</em><strong>15,900</strong>} 이 3015900).
     */
    private static String spacedText(Element element) {
        return element.nodeStream(TextNode.class)
            .map(TextNode::getWholeText)
            .collect(Collectors.joining(" "))
            .strip();
    }

    /** 변환 함수가 null 을 돌려주면 값 불량이다. */
    private static <T> Field<T> read(
        Source source,
        Document document,
        Function<Element, Optional<JsonNode>> jsonOf,
        Function<Element, String> elementText,
        Function<String, T> fromText,
        Function<JsonNode, T> fromJson
    ) {
        return switch (source) {
            case null -> Field.unspecified();
            case Css css -> readCss(css, document, elementText, fromText);
            case Json json -> readJson(json, document, jsonOf, fromJson);
        };
    }

    private static <T> Field<T> readCss(
        Css css,
        Document document,
        Function<Element, String> elementText,
        Function<String, T> fromText
    ) {
        Element element = Collector.findFirst(css.selector(), document);
        if (element == null) {
            return Field.absent();
        }
        String text = css.attribute().isEmpty() ? elementText.apply(element) : element.attr(css.attribute()).strip();
        return readOrInvalid(text == null ? null : fromText.apply(text));
    }

    private static <T> Field<T> readJson(
        Json json,
        Document document,
        Function<Element, Optional<JsonNode>> jsonOf,
        Function<JsonNode, T> fromJson
    ) {
        Element script = Collector.findFirst(json.script(), document);
        if (script == null) {
            return Field.invalid();
        }
        Optional<JsonNode> root = jsonOf.apply(script);
        if (root.isEmpty()) {
            return Field.invalid();
        }
        JsonNode node = root.get().at(json.pointer());
        if (node.isMissingNode() || node.isNull()) {
            return Field.absent();
        }
        return readOrInvalid(fromJson.apply(node));
    }

    private static <T> Field<T> readOrInvalid(T value) {
        return value == null ? Field.invalid() : Field.read(value);
    }

    private static String textOf(String text) {
        return text.isBlank() ? null : text;
    }

    private static String textOf(JsonNode node) {
        return node.isString() ? textOf(node.stringValue().strip()) : null;
    }

    /**
     * 금액은 자릿수 쉼표가 든 양의 정수 하나. 퍼센트가 붙은 수와 소수는 세지 않고, 금액이 둘 이상이면 고르지 않는다.
     * 쉼표 자리가 어긋났거나 음수 부호가 붙은 수는 금액으로 읽지 않고 전체를 값 불량으로 본다 — 두 숫자가 붙었거나
     * 할인액이라는 신호다.
     */
    private static Integer amountOf(String text) {
        Matcher matcher = NUMBER.matcher(text);
        Integer found = null;
        while (matcher.find()) {
            String token = matcher.group();
            if (token.endsWith("%") || token.contains(".")) {
                continue;
            }
            if (found != null || !AMOUNT.matcher(token).matches() || negativeOrTruncated(text, matcher.start())) {
                return null;
            }
            try {
                found = Integer.parseInt(token.replace(",", ""));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return found != null && found > 0 ? found : null;
    }

    private static boolean negativeOrTruncated(String text, int tokenStart) {
        int before = tokenStart - 1;
        // 앞이 잘린 수(",900")도 온전한 금액이 아니다
        if (before >= 0 && (text.charAt(before) == ',' || text.charAt(before) == '.')) {
            return true;
        }
        while (before >= 0 && Character.isWhitespace(text.charAt(before))) {
            before--;
        }
        return before >= 0 && (text.charAt(before) == '-' || text.charAt(before) == '\u2212');
    }

    private static Integer amountOf(JsonNode node) {
        if (node.isString()) {
            return amountOf(node.stringValue());
        }
        if (!node.isIntegralNumber() || !node.canConvertToInt()) {
            return null;
        }
        return node.intValue() > 0 ? node.intValue() : null;
    }

    private static String imageOf(JsonNode node, String baseUri) {
        return node.isString() ? imageOf(node.stringValue(), baseUri) : null;
    }

    private static String imageOf(String raw, String baseUri) {
        if (raw.isBlank()) {
            return null;
        }
        String resolved = StringUtil.resolve(baseUri, raw.strip());
        return resolved.regionMatches(true, 0, HTTPS, 0, HTTPS.length()) ? resolved : null;
    }

    private static String normalizedHost(String raw) {
        String normalized = raw.strip().toLowerCase(Locale.ROOT).replaceAll("\\.+$", "");
        if (normalized.isEmpty()) {
            throw new InvalidRuleException("host 가 비었다");
        }
        return normalized;
    }

    /** 출처가 빈 FieldSource 를 "자리 없음" 으로 읽지 않는다. 모르는 출처 종류를 실은 새 규칙이 여기 걸린다. */
    private static Source source(FieldSource source, String field) {
        return switch (source.getSourceCase()) {
            case CSS -> {
                CssSource css = source.getCss();
                yield new Css(selector(css.getSelector(), field), css.getAttribute().strip());
            }
            case JSON -> {
                JsonSource json = source.getJson();
                yield new Json(selector(json.getScript(), field), pointer(json.getPointer(), field));
            }
            case SOURCE_NOT_SET -> throw new InvalidRuleException(field + " 의 출처가 비었다");
        };
    }

    private static Evaluator selector(String css, String field) {
        if (css.isBlank()) {
            throw new InvalidRuleException(field + " 의 선택자가 비었다");
        }
        if (css.length() > MAX_EXPRESSION_LENGTH) {
            throw new InvalidRuleException(field + " 의 선택자가 너무 길다");
        }
        try {
            return QueryParser.parse(css);
        } catch (Selector.SelectorParseException e) {
            throw new InvalidRuleException(field + " 의 선택자를 해석하지 못했다: " + e.getMessage());
        }
    }

    private static JsonPointer pointer(String pointer, String field) {
        if (pointer.isEmpty()) {
            throw new InvalidRuleException(field + " 의 pointer 가 비었다");
        }
        if (pointer.length() > MAX_EXPRESSION_LENGTH) {
            throw new InvalidRuleException(field + " 의 pointer 가 너무 길다");
        }
        try {
            return JsonPointer.compile(pointer);
        } catch (IllegalArgumentException e) {
            throw new InvalidRuleException(field + " 의 pointer 를 해석하지 못했다: " + e.getMessage());
        }
    }
}
