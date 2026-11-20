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
 * 구조화 태그(JSON-LD·OG)에는 판매가만 있고 정가는 페이지의 JSON 데이터 스크립트에만 있는 몰이 있다(후루츠패밀리 실측:
 * 상품 객체에 {@code price}·{@code original_price}, 할인이 없으면 {@code original_price} 가 null).
 *
 * <p>그 스크립트에는 추천 상품도 같이 실려 있어, 이름과 판매가가 구조화 결과와 모두 같은 객체에서만 읽는다.
 * 전 몰의 data island 에 도는 규칙이라 의심스러우면 읽지 않는다 — 틀린 정가나 틀린 "할인 없음" 보다 모름이 낫고,
 * 모름은 뒤의 LLM 보강이 받는다. 후보끼리 답이 갈려도 읽지 않는다.
 */
final class DataScriptOriginalPrice {

    private static final Set<String> CURRENT_PRICE_KEYS = Set.of("price", "saleprice", "sellingprice", "finalprice");
    private static final Set<String> ORIGINAL_PRICE_KEYS =
        Set.of("originalprice", "normalprice", "listprice", "regularprice", "consumerprice");

    /** 다른 통화·단위의 금액이나 무관한 수를 정가로 읽지 않기 위한 상식선 — 90% 를 넘는 할인은 정가로 믿지 않는다. */
    private static final int MAX_ORIGINAL_TO_CURRENT_RATIO = 10;

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern INTEGER_TEXT = Pattern.compile("\\d{1,9}");
    private static final Pattern POSITIVE_NUMBER_TEXT = Pattern.compile("\\s*0*[1-9]\\d*(\\.\\d+)?\\s*%?\\s*|\\s*0+\\.0*[1-9]\\d*\\s*%?\\s*");

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
            if (describesProduct(node, productName, currentPrice)) {
                answerOf(node, currentPrice).ifPresent(answers::add);
            }
            for (Map.Entry<String, JsonNode> property : node.properties()) {
                pending.push(property.getValue());
            }
        }
    }

    /**
     * 판매가 계열 칸이 구조화 결과와 같고, 이름 칸이 상품명과 같은가. 판매가 계열 칸 중 더 싼 값이 있으면 이 객체의
     * "판매가" 는 사실 정가다(JSON-LD 가 정가를 판매가로 실은 몰) — 그런 객체로 "할인 없음" 을 확정하면 안 된다.
     */
    private static boolean describesProduct(JsonNode object, String productName, int currentPrice) {
        boolean samePrice = false;
        for (Map.Entry<String, JsonNode> property : object.properties()) {
            if (!CURRENT_PRICE_KEYS.contains(normalizeKey(property.getKey()))) {
                continue;
            }
            OptionalInt amount = amountOf(property.getValue());
            if (amount.isPresent() && amount.getAsInt() > 0 && amount.getAsInt() < currentPrice) {
                return false;
            }
            samePrice |= amount.isPresent() && amount.getAsInt() == currentPrice;
        }
        return samePrice && hasName(object, productName);
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

    /** 정가 칸이 없으면 empty, 있는데 믿을 수 없으면 {@link #CONFLICT}. */
    private static Optional<Integer> answerOf(JsonNode object, int currentPrice) {
        Set<Integer> answers = new HashSet<>();
        for (Map.Entry<String, JsonNode> property : object.properties()) {
            if (!ORIGINAL_PRICE_KEYS.contains(normalizeKey(property.getKey()))) {
                continue;
            }
            JsonNode value = property.getValue();
            OptionalInt amount = amountOf(value);
            boolean blank = value.isNull() || (amount.isPresent() && amount.getAsInt() == 0);
            if (blank) {
                answers.add(suggestsDiscount(object, currentPrice) ? CONFLICT : currentPrice);
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
        return originalPrice >= currentPrice
            && originalPrice / MAX_ORIGINAL_TO_CURRENT_RATIO <= currentPrice;
    }

    /**
     * 정가 칸이 비었어도 같은 객체에 할인의 흔적이 있으면 "할인 없음" 이 아니다. 정가를 목록 밖 키에 두거나
     * (retailPrice 등 판매가보다 큰 가격 칸) 할인을 다른 모양(할인율·할인 객체)으로 싣는 몰이 있다.
     */
    private static boolean suggestsDiscount(JsonNode object, int currentPrice) {
        for (Map.Entry<String, JsonNode> property : object.properties()) {
            String key = normalizeKey(property.getKey());
            JsonNode value = property.getValue();
            if (key.contains("discount") || key.contains("salerate")) {
                if (value.isObject() || value.isArray() || isPositive(value)) {
                    return true;
                }
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
