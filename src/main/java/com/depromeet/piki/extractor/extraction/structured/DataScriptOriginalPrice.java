package com.depromeet.piki.extractor.extraction.structured;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.regex.Pattern;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 구조화 태그(JSON-LD·OG)에는 판매가만 있고 정가는 페이지의 JSON 데이터 스크립트에만 있는 몰이 있다. 실측한 모양은 둘이다:
 * <ul>
 *   <li>상품 객체에 가격 칸이 나란히 있음 — {@code price}·{@code original_price}(후루츠패밀리. 할인이 없으면 null)</li>
 *   <li>상품 객체의 {@code price} 칸이 객체 — {@code price: {sale, original}}(sixshop 계열)</li>
 * </ul>
 *
 * <p>그 스크립트에는 추천 상품도 같이 실려 있어, 이름과 판매가가 구조화 결과와 모두 같은 상품에서만 읽는다.
 * 전 몰의 data island 에 도는 규칙이라 의심스러우면 읽지 않는다 — 틀린 정가나 틀린 "할인 없음" 보다 모름이 낫고,
 * 모름은 뒤의 LLM 보강이 받는다. 후보끼리 답이 갈려도 읽지 않는다.
 */
final class DataScriptOriginalPrice {

    private static final Set<String> CURRENT_PRICE_KEYS = Set.of("price", "saleprice", "sellingprice", "finalprice");
    private static final Set<String> ORIGINAL_PRICE_KEYS =
        Set.of("originalprice", "normalprice", "listprice", "regularprice", "consumerprice");

    private static final Set<String> NESTED_CURRENT_PRICE_KEYS = Set.of("sale", "selling", "final", "current");
    private static final Set<String> NESTED_ORIGINAL_PRICE_KEYS =
        Set.of("original", "regular", "list", "normal", "consumer", "before");

    /** 다른 통화·단위의 금액이나 무관한 수를 정가로 읽지 않기 위한 상식선 — 90% 를 넘는 할인은 정가로 믿지 않는다. */
    private static final int MAX_ORIGINAL_TO_CURRENT_RATIO = 10;

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern INTEGER_TEXT = Pattern.compile("\\d{1,9}");
    private static final Pattern POSITIVE_NUMBER_TEXT =
        Pattern.compile("\\s*0*[1-9]\\d*(\\.\\d+)?\\s*%?\\s*|\\s*0+\\.0*[1-9]\\d*\\s*%?\\s*");

    /** 후보끼리 답이 갈렸다는 표시. 금액은 음수가 될 수 없어 실제 답과 겹치지 않는다. */
    private static final int CONFLICT = -1;

    private DataScriptOriginalPrice() {
    }

    /** 할인이 없다고 읽히면 판매가와 같은 값을 돌려준다. 읽을 수 없으면 empty. */
    static OptionalInt find(Document document, ObjectMapper objectMapper, String name, int currentPrice) {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(objectMapper, "objectMapper");
        String productName = normalizeText(Objects.requireNonNull(name, "name"));
        String priceDigits = String.valueOf(currentPrice);
        Set<Integer> answers = new HashSet<>();
        for (Element script : document.select("script[type]")) {
            if (!isJsonDataScript(script)) {
                continue;
            }
            String json = script.data();
            // 판매가가 글자로도 없는 island 는 후보가 나올 수 없다 — 수백 KB 트리를 만들지 않는다
            if (!json.contains(priceDigits)) {
                continue;
            }
            JsonNode root;
            try {
                root = objectMapper.readTree(json);
            } catch (JacksonException e) {
                continue;
            }
            collectAnswers(root, productName, currentPrice, answers);
        }
        if (answers.size() != 1 || answers.contains(CONFLICT)) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(answers.iterator().next());
    }

    /** JSON-LD 는 구조화 파서가 이미 읽었으므로 일반 JSON data island 만 본다. */
    private static boolean isJsonDataScript(Element script) {
        return script.attr("type").trim().toLowerCase(Locale.ROOT).startsWith("application/json");
    }

    private static void collectAnswers(JsonNode root, String productName, int currentPrice, Set<Integer> answers) {
        Deque<JsonNode> pending = new ArrayDeque<>();
        pending.push(root);
        while (!pending.isEmpty()) {
            JsonNode node = pending.pop();
            if (node.isArray()) {
                for (int i = 0; i < node.size(); i++) {
                    pending.push(node.get(i));
                }
                continue;
            }
            if (!node.isObject()) {
                continue;
            }
            if (hasName(node, productName)) {
                flatAnswer(node, currentPrice).ifPresent(answers::add);
                nestedAnswer(node, currentPrice).ifPresent(answers::add);
            }
            for (Map.Entry<String, JsonNode> property : node.properties()) {
                pending.push(property.getValue());
            }
        }
    }

    /** 이름 칸만 본다 — 모든 문자열 칸과 비교하면 브랜드명·옵션명이 상품명과 같은 객체가 후보가 된다. */
    private static boolean hasName(JsonNode object, String productName) {
        for (Map.Entry<String, JsonNode> property : object.properties()) {
            String key = normalizeKey(property.getKey());
            JsonNode value = property.getValue();
            if ((key.endsWith("name") || key.endsWith("title"))
                && value.isString()
                && productName.equals(normalizeText(value.asString()))) {
                return true;
            }
        }
        return false;
    }

    private static Optional<Integer> flatAnswer(JsonNode product, int currentPrice) {
        if (!hasAmount(product, CURRENT_PRICE_KEYS, currentPrice) || hasCheaperAmount(product, CURRENT_PRICE_KEYS, currentPrice)) {
            return Optional.empty();
        }
        return answerOf(product, ORIGINAL_PRICE_KEYS, currentPrice, !suggestsDiscount(product, currentPrice));
    }

    /**
     * 가격 객체는 {@code price} 칸 하나만 본다 — {@code *Price} 로 넓히면 배송비·회원가 객체가 상품가와 값이 같을 때
     * 후보가 된다. 안쪽은 키 이름이 제각각이라(retail·msrp·compareAt 등) "할인 없음" 은 값과 모양까지 봐서
     * 깨끗할 때만 믿는다.
     */
    private static Optional<Integer> nestedAnswer(JsonNode product, int currentPrice) {
        JsonNode priceObject = null;
        for (Map.Entry<String, JsonNode> property : product.properties()) {
            if (normalizeKey(property.getKey()).equals("price") && property.getValue().isObject()) {
                priceObject = property.getValue();
            }
        }
        if (priceObject == null
            || !hasAmount(priceObject, NESTED_CURRENT_PRICE_KEYS, currentPrice)
            || hasCheaperAmount(priceObject, NESTED_CURRENT_PRICE_KEYS, currentPrice)
            || hasCheaperAmount(product, CURRENT_PRICE_KEYS, currentPrice)) {
            return Optional.empty();
        }
        boolean noDiscountTrusted = !suggestsDiscount(product, currentPrice)
            && !suggestsDiscount(priceObject, currentPrice)
            && !hasOtherAmountOrContainer(priceObject, currentPrice);
        return answerOf(priceObject, NESTED_ORIGINAL_PRICE_KEYS, currentPrice, noDiscountTrusted);
    }

    private static boolean hasAmount(JsonNode priceHolder, Set<String> keys, int expected) {
        for (Map.Entry<String, JsonNode> property : priceHolder.properties()) {
            if (!keys.contains(normalizeKey(property.getKey()))) {
                continue;
            }
            OptionalInt amount = amountOf(property.getValue());
            if (amount.isPresent() && amount.getAsInt() == expected) {
                return true;
            }
        }
        return false;
    }

    /**
     * 판매가 계열 칸에 더 싼 값이 있으면 구조화 결과의 "판매가" 는 사실 정가다(JSON-LD 가 정가를 판매가로 실은 몰) —
     * 그런 객체로 "할인 없음" 을 확정하면 안 된다.
     */
    private static boolean hasCheaperAmount(JsonNode priceHolder, Set<String> keys, int currentPrice) {
        for (Map.Entry<String, JsonNode> property : priceHolder.properties()) {
            if (!keys.contains(normalizeKey(property.getKey()))) {
                continue;
            }
            OptionalInt amount = amountOf(property.getValue());
            if (amount.isPresent() && amount.getAsInt() > 0 && amount.getAsInt() < currentPrice) {
                return true;
            }
        }
        return false;
    }

    /**
     * 정가 칸이 없으면 empty, 있는데 믿을 수 없으면 {@link #CONFLICT}. 정가 칸이 비었거나 판매가와 같은 것은
     * 둘 다 "할인 없음" 이라는 주장이라, 할인의 흔적이 없을 때만 받는다.
     */
    private static Optional<Integer> answerOf(
        JsonNode priceHolder,
        Set<String> originalPriceKeys,
        int currentPrice,
        boolean noDiscountTrusted
    ) {
        Set<Integer> answers = new HashSet<>();
        for (Map.Entry<String, JsonNode> property : priceHolder.properties()) {
            if (!originalPriceKeys.contains(normalizeKey(property.getKey()))) {
                continue;
            }
            JsonNode value = property.getValue();
            OptionalInt amount = amountOf(value);
            boolean blank = value.isNull() || (amount.isPresent() && amount.getAsInt() == 0);
            if (blank || (amount.isPresent() && amount.getAsInt() == currentPrice)) {
                answers.add(noDiscountTrusted ? currentPrice : CONFLICT);
            } else if (amount.isPresent() && isPlausibleOriginalPrice(amount.getAsInt(), currentPrice)) {
                answers.add(amount.getAsInt());
            } else {
                answers.add(CONFLICT);
            }
        }
        if (answers.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(answers.size() == 1 ? answers.iterator().next() : CONFLICT);
    }

    private static boolean isPlausibleOriginalPrice(int originalPrice, int currentPrice) {
        return originalPrice > currentPrice
            && originalPrice / MAX_ORIGINAL_TO_CURRENT_RATIO <= currentPrice;
    }

    /**
     * 같은 객체에 할인의 흔적이 있는가. 정가를 목록 밖 키에 두거나(retailPrice 등 판매가보다 큰 가격 칸) 할인을
     * 다른 모양(할인율·할인 객체)으로 싣는 몰이 있다. 할인 객체는 양수가 들어 있을 때만 흔적이다 —
     * 할인이 없어도 0 으로 채운 할인 객체나 빈 배열을 늘 싣는 몰이 있다.
     */
    private static boolean suggestsDiscount(JsonNode object, int currentPrice) {
        for (Map.Entry<String, JsonNode> property : object.properties()) {
            String key = normalizeKey(property.getKey());
            JsonNode value = property.getValue();
            if ((key.contains("discount") || key.contains("salerate")) && carriesPositive(value)) {
                return true;
            }
            if (key.endsWith("price")) {
                OptionalInt amount = amountOf(value);
                if (amount.isPresent() && amount.getAsInt() > currentPrice) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean carriesPositive(JsonNode value) {
        if (value.isObject()) {
            for (Map.Entry<String, JsonNode> property : value.properties()) {
                if (isPositive(property.getValue())) {
                    return true;
                }
            }
            return false;
        }
        if (value.isArray()) {
            for (int i = 0; i < value.size(); i++) {
                if (carriesPositive(value.get(i))) {
                    return true;
                }
            }
            return false;
        }
        return isPositive(value);
    }

    /** 판매가와 다른 금액이나 안쪽 객체·배열이 하나라도 있으면 그 가격 객체는 판매가 하나만 말하는 것이 아니다. */
    private static boolean hasOtherAmountOrContainer(JsonNode priceObject, int currentPrice) {
        for (Map.Entry<String, JsonNode> property : priceObject.properties()) {
            JsonNode value = property.getValue();
            if (value.isObject() || value.isArray()) {
                return true;
            }
            OptionalInt amount = amountOf(value);
            if (amount.isPresent() && amount.getAsInt() > 0 && amount.getAsInt() != currentPrice) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPositive(JsonNode value) {
        if (value.isNumber()) {
            Optional<BigDecimal> number = value.decimalValueOpt();
            return number.isPresent() && number.get().signum() > 0;
        }
        return value.isString() && POSITIVE_NUMBER_TEXT.matcher(value.asString()).matches();
    }

    /** 정수로 떨어지는 금액만. {@code 1e400} 같은 값은 변환 예외를 내므로 Opt 계열로만 읽는다. */
    private static OptionalInt amountOf(JsonNode value) {
        if (value.isNumber()) {
            return value.intValueOpt();
        }
        if (value.isString() && INTEGER_TEXT.matcher(value.asString()).matches()) {
            return OptionalInt.of(Integer.parseInt(value.asString()));
        }
        return OptionalInt.empty();
    }

    private static String normalizeKey(String key) {
        return key.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
    }

    private static String normalizeText(String text) {
        return WHITESPACE.matcher(text.trim()).replaceAll(" ");
    }
}
