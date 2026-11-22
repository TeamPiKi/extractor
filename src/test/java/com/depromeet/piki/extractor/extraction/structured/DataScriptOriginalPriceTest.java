package com.depromeet.piki.extractor.extraction.structured;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalInt;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class DataScriptOriginalPriceTest {

    private static final String NAME = "GANNI Naplack Penny Loafer";

    private OptionalInt find(String json, int currentPrice) {
        String html = "<html><head><script id=\"__APOLLO_STATE__\" type=\"application/json\">" + json
            + "</script></head><body></body></html>";
        return DataScriptOriginalPrice.find(Jsoup.parse(html), new ObjectMapper(), NAME, currentPrice);
    }

    @Test
    @DisplayName("이름과 판매가가 같은 객체의 정가 칸이 비어 있으면 할인 없음(정가 = 판매가)으로 읽는다")
    void readsNoDiscountWhenOriginalPriceIsNull() {
        OptionalInt found = find("""
            {"ProductNotMine:1":{"title":"GANNI Naplack Penny Loafer","price":200000,"original_price":null,"discount_rate":null}}""",
            200_000);

        assertEquals(OptionalInt.of(200_000), found);
    }

    @Test
    @DisplayName("이름과 판매가가 같은 객체의 정가가 판매가보다 크면 그 값을 정가로 읽는다")
    void readsOriginalPriceWhenDiscounted() {
        OptionalInt found = find("""
            {"ProductNotMine:1":{"title":"GANNI Naplack Penny Loafer","price":75000,"original_price":125000,"discount_rate":40}}""",
            75_000);

        assertEquals(OptionalInt.of(125_000), found);
    }

    @Test
    @DisplayName("같은 데이터에 실린 다른 상품(추천 목록)의 정가는 읽지 않는다")
    void ignoresOtherProductsInTheSameScript() {
        OptionalInt found = find("""
            {"ProductNotMine:1":{"title":"GANNI Naplack Penny Loafer","price":200000,"original_price":null},
             "ProductNotMine:2":{"title":"다른 로퍼","price":200000,"original_price":290000},
             "list":[{"title":"또 다른 부츠","price":177000,"original_price":180000}]}""",
            200_000);

        assertEquals(OptionalInt.of(200_000), found);
    }

    @Test
    @DisplayName("이름과 판매가가 같은 객체들의 정가가 서로 다르면 읽지 않는다")
    void givesUpWhenCandidatesDisagree() {
        OptionalInt found = find("""
            [{"title":"GANNI Naplack Penny Loafer","price":200000,"original_price":250000},
             {"title":"GANNI Naplack Penny Loafer","price":200000,"original_price":null}]""",
            200_000);

        assertTrue(found.isEmpty());
    }

    @Test
    @DisplayName("이름과 판매가가 같은 객체가 여럿이어도 정가가 같으면 그 값을 읽는다")
    void acceptsAgreeingCandidates() {
        OptionalInt found = find("""
            [{"title":"GANNI Naplack Penny Loafer","price":200000,"original_price":250000},
             {"name":"GANNI Naplack Penny Loafer","salePrice":200000,"originalPrice":250000}]""",
            200_000);

        assertEquals(OptionalInt.of(250_000), found);
    }

    @Test
    @DisplayName("정가 칸은 비었는데 할인율이 0 보다 크면 할인 없음으로 읽지 않는다")
    void doesNotReadNoDiscountWhenDiscountRateIsPositive() {
        OptionalInt found = find("""
            {"title":"GANNI Naplack Penny Loafer","price":200000,"original_price":null,"discount_rate":20}""",
            200_000);

        assertTrue(found.isEmpty());
    }

    @Test
    @DisplayName("할인율이 숫자 문자열이어도 0 보다 크면 할인 없음으로 읽지 않는다")
    void doesNotReadNoDiscountWhenDiscountRateIsPositiveText() {
        assertTrue(find("""
            {"title":"GANNI Naplack Penny Loafer","price":"200000","original_price":null,"discount_rate":"20"}""",
            200_000).isEmpty());
        assertTrue(find("""
            {"title":"GANNI Naplack Penny Loafer","price":200000,"originalPrice":0,"discountRatio":"20%"}""",
            200_000).isEmpty());
    }

    @Test
    @DisplayName("할인 정보가 중첩 객체로 실려 있으면 정가 칸이 비어도 할인 없음으로 읽지 않는다")
    void doesNotReadNoDiscountWhenDiscountIsNestedObject() {
        OptionalInt found = find("""
            {"title":"GANNI Naplack Penny Loafer","price":200000,"original_price":null,
             "discount":{"rate":20,"before":250000}}""",
            200_000);

        assertTrue(found.isEmpty());
    }

    @Test
    @DisplayName("정가 칸은 비었는데 판매가보다 큰 다른 가격 칸이 있으면 할인 없음으로 읽지 않는다")
    void doesNotReadNoDiscountWhenAnotherPriceIsHigher() {
        assertTrue(find("""
            {"title":"GANNI Naplack Penny Loafer","price":200000,"originalPrice":null,"retailPrice":250000}""",
            200_000).isEmpty());
        assertTrue(find("""
            {"title":"GANNI Naplack Penny Loafer","price":250000,"sale_price":200000,"original_price":null}""",
            200_000).isEmpty());
    }

    @Test
    @DisplayName("정가 칸이 0 이고 할인율도 0 이면 할인 없음으로 읽는다")
    void readsNoDiscountWhenOriginalPriceAndDiscountRateAreZero() {
        OptionalInt found = find("""
            {"title":"GANNI Naplack Penny Loafer","price":200000,"original_price":"0","discount_rate":0}""",
            200_000);

        assertEquals(OptionalInt.of(200_000), found);
    }

    @Test
    @DisplayName("판매가 계열 칸에 더 싼 값이 있는 객체는 그 판매가가 사실 정가라 후보로 삼지 않는다")
    void ignoresObjectWhoseMatchedPriceIsActuallyListPrice() {
        OptionalInt found = find("""
            {"title":"GANNI Naplack Penny Loafer","price":200000,"salePrice":150000,"originalPrice":200000}""",
            200_000);

        assertTrue(found.isEmpty());
    }

    @Test
    @DisplayName("이름 칸이 아닌 문자열이 상품명과 같아도 후보로 삼지 않는다")
    void matchesNameOnlyInNameKeys() {
        OptionalInt found = find("""
            {"brand":"GANNI Naplack Penny Loafer","price":200000,"listPrice":300000}""",
            200_000);

        assertTrue(found.isEmpty());
    }

    @Test
    @DisplayName("이름은 같아도 판매가가 다른 객체는 후보로 삼지 않는다")
    void requiresSamePrice() {
        OptionalInt found = find("""
            {"title":"GANNI Naplack Penny Loafer","price":210000,"original_price":250000}""",
            200_000);

        assertTrue(found.isEmpty());
    }

    @Test
    @DisplayName("판매가의 10배를 넘는 값은 정가로 믿지 않는다")
    void ignoresImplausiblyHighOriginalPrice() {
        OptionalInt found = find("""
            {"title":"GANNI Naplack Penny Loafer","price":200000,"original_price":9900000}""",
            200_000);

        assertTrue(found.isEmpty());
    }

    @Test
    @DisplayName("표현할 수 없는 숫자가 섞여 있어도 예외 없이 건너뛴다")
    void survivesUnrepresentableNumbers() {
        assertTrue(find("""
            {"x":{"price":1e400},"title":"GANNI Naplack Penny Loafer","price":200000.5,"original_price":99999999999}""",
            200_000).isEmpty());
        assertEquals(OptionalInt.of(250_000), find("""
            {"x":{"price":1e400,"discount_rate":1e400},
             "p":{"title":"GANNI Naplack Penny Loafer","price":200000.0,"original_price":250000.00}}""",
            200_000));
    }

    @Test
    @DisplayName("한 객체의 정가 칸들이 서로 다르면 읽지 않는다")
    void givesUpWhenOriginalPriceKeysDisagreeInOneObject() {
        OptionalInt found = find("""
            {"title":"GANNI Naplack Penny Loafer","price":200000,"original_price":250000,"listPrice":230000}""",
            200_000);

        assertTrue(found.isEmpty());
    }

    @Test
    @DisplayName("믿을 수 없는 정가를 가진 후보가 하나라도 있으면 다른 후보의 답도 쓰지 않는다")
    void givesUpWhenAnyCandidateIsUntrustworthy() {
        OptionalInt found = find("""
            [{"title":"GANNI Naplack Penny Loafer","price":200000,"original_price":150000},
             {"title":"GANNI Naplack Penny Loafer","price":200000,"original_price":null}]""",
            200_000);

        assertTrue(found.isEmpty());
    }

    @Test
    @DisplayName("정가가 판매가보다 작으면 읽지 않는다")
    void ignoresOriginalPriceBelowCurrentPrice() {
        OptionalInt found = find("""
            {"title":"GANNI Naplack Penny Loafer","price":200000,"original_price":150000}""",
            200_000);

        assertTrue(found.isEmpty());
    }

    @Test
    @DisplayName("판매가는 같아도 이름이 다르면 읽지 않는다")
    void requiresSameName() {
        OptionalInt found = find("""
            {"title":"GANNI Naplack Penny Loafer (리셀)","price":200000,"original_price":250000}""",
            200_000);

        assertTrue(found.isEmpty());
    }

    @Test
    @DisplayName("camelCase 키와 숫자 문자열 값도 같은 규칙으로 읽는다")
    void readsCamelCaseKeysAndNumericStrings() {
        OptionalInt found = find("""
            {"props":{"pageProps":{"product":{"name":"GANNI  Naplack Penny Loafer ","sellingPrice":"200000","listPrice":"250000"}}}}""",
            200_000);

        assertEquals(OptionalInt.of(250_000), found);
    }

    @Test
    @DisplayName("정가 키가 없는 객체는 이름과 판매가가 같아도 답을 내지 않는다")
    void needsAnOriginalPriceKey() {
        OptionalInt found = find("""
            {"title":"GANNI Naplack Penny Loafer","price":200000}""",
            200_000);

        assertTrue(found.isEmpty());
    }

    @Test
    @DisplayName("가격 칸이 객체면 그 안의 판매가와 정가를 읽는다")
    void readsNestedPriceObject() {
        OptionalInt found = find("""
            {"resource":{"name":"GANNI Naplack Penny Loafer","price":{"original":280000,"sale":200000},
              "discount":{"type":"percentage","value":28},
              "variants":[{"price":{"original":280000,"sale":200000}},{"price":{"original":280000,"sale":200000}}]}}""",
            200_000);

        assertEquals(OptionalInt.of(280_000), found);
    }

    @Test
    @DisplayName("안쪽 가격 객체의 정가와 판매가가 같으면 할인 없음으로 읽는다")
    void readsNoDiscountFromNestedPriceObject() {
        OptionalInt found = find("""
            {"name":"GANNI Naplack Penny Loafer","price":{"original":200000,"sale":200000},"discounts":[]}""",
            200_000);

        assertEquals(OptionalInt.of(200_000), found);
    }

    @Test
    @DisplayName("안쪽 가격 객체의 정가 칸이 비었는데 상품 객체에 할인 정보가 있으면 읽지 않는다")
    void doesNotReadNoDiscountFromNestedPriceObjectWhenProductHasDiscount() {
        assertTrue(find("""
            {"name":"GANNI Naplack Penny Loafer","price":{"original":null,"sale":200000},
             "discount":{"type":"percentage","value":20}}""",
            200_000).isEmpty());
        assertTrue(find("""
            {"name":"GANNI Naplack Penny Loafer","price":{"original":null,"sale":200000,"retail":250000}}""",
            200_000).isEmpty());
    }

    @Test
    @DisplayName("안쪽 가격 객체의 정가 칸이 비었고 할인 정보가 빈 객체·빈 배열이면 할인 없음으로 읽는다")
    void readsNoDiscountWhenDiscountContainersAreEmpty() {
        OptionalInt found = find("""
            {"name":"GANNI Naplack Penny Loafer","price":{"original":null,"sale":200000},"discount":{},"discounts":[]}""",
            200_000);

        assertEquals(OptionalInt.of(200_000), found);
    }

    @Test
    @DisplayName("안쪽 가격 객체의 판매가가 다르면 읽지 않는다")
    void requiresSamePriceInNestedPriceObject() {
        OptionalInt found = find("""
            {"name":"GANNI Naplack Penny Loafer","price":{"original":280000,"sale":210000}}""",
            200_000);

        assertTrue(found.isEmpty());
    }

    @Test
    @DisplayName("이름이 다른 객체의 안쪽 가격 객체는 읽지 않는다")
    void requiresSameNameForNestedPriceObject() {
        OptionalInt found = find("""
            {"name":"다른 로퍼","price":{"original":280000,"sale":200000}}""",
            200_000);

        assertTrue(found.isEmpty());
    }

    @Test
    @DisplayName("같은 상품 객체가 스크립트에 두 번 실려 있어도 답이 같으면 그 값을 읽는다")
    void readsNestedPriceObjectRepeatedInOneScript() {
        OptionalInt found = find("""
            {"queries":[{"data":{"name":"GANNI Naplack Penny Loafer","price":{"original":280000,"sale":200000}}},
                        {"data":{"name":"GANNI Naplack Penny Loafer","price":{"original":280000,"sale":200000}}}]}""",
            200_000);

        assertEquals(OptionalInt.of(280_000), found);
    }

    @Test
    @DisplayName("안쪽 가격 객체 안에 할인 흔적이 있으면 정가 칸이 비어도 할인 없음으로 읽지 않는다")
    void doesNotReadNoDiscountWhenNestedPriceObjectCarriesDiscount() {
        assertTrue(find("""
            {"name":"GANNI Naplack Penny Loafer","price":{"original":null,"sale":200000,"discountRate":20}}""",
            200_000).isEmpty());
        assertTrue(find("""
            {"name":"GANNI Naplack Penny Loafer","price":{"original":0,"sale":200000,"discount":50000}}""",
            200_000).isEmpty());
        assertTrue(find("""
            {"name":"GANNI Naplack Penny Loafer","price":{"original":null,"sale":200000,"compareAt":{"amount":250000}}}""",
            200_000).isEmpty());
    }

    @Test
    @DisplayName("price 가 아닌 다른 가격 객체(배송비·회원가)는 값이 판매가와 같아도 읽지 않는다")
    void readsOnlyThePriceObject() {
        assertTrue(find("""
            {"name":"GANNI Naplack Penny Loafer","price":200000,"marketPrice":250000,
             "shippingPrice":{"sale":200000,"original":200000}}""",
            200_000).isEmpty());
        assertTrue(find("""
            {"name":"GANNI Naplack Penny Loafer","price":200000,"memberPrice":{"sale":200000,"original":230000}}""",
            200_000).isEmpty());
    }

    @Test
    @DisplayName("안쪽 가격 객체에 판매가보다 싼 금액이 있으면 정가와 판매가가 같아도 할인 없음으로 읽지 않는다")
    void doesNotReadNoDiscountWhenNestedPriceObjectHasCheaperAmount() {
        assertTrue(find("""
            {"name":"GANNI Naplack Penny Loafer","price":{"original":200000,"sale":200000,"discounted":150000}}""",
            200_000).isEmpty());
        assertTrue(find("""
            {"name":"GANNI Naplack Penny Loafer","price":{"sale":200000,"final":150000,"original":200000}}""",
            200_000).isEmpty());
        assertTrue(find("""
            {"name":"GANNI Naplack Penny Loafer","salePrice":150000,"price":{"sale":200000,"original":200000}}""",
            200_000).isEmpty());
    }

    @Test
    @DisplayName("정가와 판매가가 같게 적혀 있어도 할인 정보에 양수가 있으면 할인 없음으로 읽지 않는다")
    void doesNotReadNoDiscountWhenEqualPricesContradictDiscount() {
        assertTrue(find("""
            {"name":"GANNI Naplack Penny Loafer","price":{"original":200000,"sale":200000},
             "discount":{"type":"percentage","value":40,"discounted":80000}}""",
            200_000).isEmpty());
        assertTrue(find("""
            {"title":"GANNI Naplack Penny Loafer","price":200000,"originalPrice":200000,"discountRate":40}""",
            200_000).isEmpty());
    }

    @Test
    @DisplayName("0 으로 채운 할인 객체와 빈 할인 배열은 할인의 흔적으로 보지 않는다")
    void treatsZeroFilledDiscountAsNoDiscount() {
        assertEquals(OptionalInt.of(200_000), find("""
            {"name":"GANNI Naplack Penny Loafer","price":{"original":200000,"sale":200000},
             "discount":{"type":null,"discounted":0,"value":0},"discounts":[{"value":0}]}""",
            200_000));
        assertEquals(OptionalInt.of(200_000), find("""
            {"title":"GANNI Naplack Penny Loafer","price":200000,"originalPrice":null,"discounts":[]}""",
            200_000));
    }

    @Test
    @DisplayName("한 객체의 나란한 가격 칸과 안쪽 가격 객체가 서로 다른 정가를 말하면 읽지 않는다")
    void givesUpWhenFlatAndNestedAnswersDisagree() {
        OptionalInt found = find("""
            [{"name":"GANNI Naplack Penny Loafer","salePrice":200000,"originalPrice":250000},
             {"name":"GANNI Naplack Penny Loafer","price":{"sale":200000,"original":280000}}]""",
            200_000);

        assertTrue(found.isEmpty());
    }

    @Test
    @DisplayName("깨진 JSON 스크립트는 건너뛰고 JSON-LD 스크립트는 보지 않는다")
    void skipsBrokenJsonAndIgnoresJsonLd() {
        String html = "<html><head>"
            + "<script type=\"application/json\">{broken</script>"
            + "<script type=\"application/ld+json\">{\"name\":\"GANNI Naplack Penny Loafer\",\"price\":200000,"
            + "\"original_price\":250000}</script>"
            + "<script type=\"application/json; charset=utf-8\">{\"title\":\"GANNI Naplack Penny Loafer\","
            + "\"price\":200000,\"original_price\":230000}</script>"
            + "</head><body></body></html>";

        OptionalInt found = DataScriptOriginalPrice.find(Jsoup.parse(html), new ObjectMapper(), NAME, 200_000);

        assertEquals(OptionalInt.of(230_000), found);
    }
}
