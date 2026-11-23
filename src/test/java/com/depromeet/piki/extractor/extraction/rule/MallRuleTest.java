package com.depromeet.piki.extractor.extraction.rule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.depromeet.piki.extractor.domain.ProductLink;
import com.depromeet.piki.extractor.extraction.PageContent;
import com.depromeet.piki.extractor.extraction.rule.RuleReading.State;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

class MallRuleTest {

    private static final ProductLink LINK = ProductLink.parse("https://www.oliveyoung.co.kr/store/goods/1");

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static Document document(String html) {
        return PageContent.of(LINK, html).document();
    }

    private RuleReading read(String ruleJson, String html) {
        return RuleFixtures.rule(ruleJson).read(document(html), objectMapper);
    }

    private RuleReading readPrice(String priceText) {
        return read(
            "{\"host\": \"oliveyoung.co.kr\", \"currentPrice\": {\"css\": {\"selector\": \".price\"}}}",
            "<html><body><div class=\"price\">" + priceText + "</div></body></html>"
        );
    }

    @Test
    @DisplayName("클래스 접두 일치 선택자로 렌더 화면의 이름·판매가·정가와 메타 태그의 이미지를 읽는다")
    void readsRenderedDomByClassPrefix() {
        RuleReading reading = read(RuleFixtures.OLIVE_YOUNG, """
            <html><head><meta property="og:image" content="https://image.oliveyoung.co.kr/a.jpg"></head><body>
              <h3 class="GoodsDetailInfo_title__x9Zk2">라운드랩 독도 토너</h3>
              <div class="GoodsDetailInfo_price-before__Qw1"><span>20,000</span>원</div>
              <div class="GoodsDetailInfo_price__7hJ3a"><span>15,900</span>원</div>
            </body></html>""");

        assertEquals("라운드랩 독도 토너", reading.name().value());
        assertEquals("https://image.oliveyoung.co.kr/a.jpg", reading.imageUrl().value());
        assertEquals(15_900, reading.currentPrice().value());
        assertEquals(20_000, reading.originalPrice().value());
    }

    @Test
    @DisplayName("선택자에 맞는 요소가 없으면 자리 없음이고, 출처를 적지 않은 필드는 미지정이다")
    void missingElementIsAbsent() {
        RuleReading reading = read(
            """
            {"host": "oliveyoung.co.kr",
             "currentPrice": {"css": {"selector": ".price"}},
             "originalPrice": {"css": {"selector": ".before"}}}""",
            "<html><body><div class=\"price\">15,900원</div></body></html>"
        );

        assertEquals(State.READ, reading.currentPrice().state());
        assertEquals(State.ABSENT, reading.originalPrice().state());
        assertEquals(State.UNSPECIFIED, reading.name().state());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "15,900원 | 15900",
        "15900 | 15900",
        "20% 15,900원 | 15900",
        "판매가 15,900 원 | 15900",
    })
    @DisplayName("금액은 자릿수 쉼표가 든 정수 하나로 읽고 퍼센트가 붙은 수는 세지 않는다")
    void readsSingleAmount(String text, int expected) {
        assertEquals(expected, readPrice(text).currentPrice().value());
    }

    @ParameterizedTest
    @ValueSource(strings = {"20,000 15,900", "1+1 15,900원", "15,900.50", "가격 문의", "0", "99,999,999,999"})
    @DisplayName("금액이 없거나 둘 이상이거나 정수가 아니면 값 불량이다")
    void ambiguousAmountIsInvalid(String text) {
        assertEquals(State.INVALID, readPrice(text).currentPrice().state());
    }

    @ParameterizedTest
    @ValueSource(strings = {"1,5900", "15,,900", "3015,900", ",900원", "-15,900", "- 3,000원", "\u22123,000"})
    @DisplayName("쉼표 자리가 어긋났거나 음수 부호가 붙은 수는 금액으로 읽지 않는다")
    void malformedOrNegativeAmountIsInvalid(String text) {
        assertEquals(State.INVALID, readPrice(text).currentPrice().state());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "<del>9,900</del><ins>7,900</ins>",
        "<em>30</em><strong>15,900</strong>",
        "<span>15</span><span>900</span>",
    })
    @DisplayName("붙어 있는 요소들의 숫자를 한 금액으로 이어 읽지 않는다")
    void doesNotGlueNumbersAcrossElements(String inner) {
        assertEquals(State.INVALID, readPrice(inner).currentPrice().state());
    }

    @Test
    @DisplayName("금액과 단위가 다른 요소에 나뉘어 있어도 금액 하나로 읽는다")
    void readsAmountSplitFromItsUnit() {
        assertEquals(15_900, readPrice("<span>15,900</span><span>원</span>").currentPrice().value());
    }

    @Test
    @DisplayName("지나치게 긴 선택자는 해석하지 않는다")
    void rejectsOverlongSelector() {
        String selector = "div" + " > div".repeat(200);

        assertThrows(InvalidRuleException.class, () -> RuleFixtures.rule(
            "{\"host\": \"a.com\", \"name\": {\"css\": {\"selector\": \"" + selector + "\"}}}"));
    }

    @Test
    @DisplayName("데이터 스크립트의 JSON 경로로 값을 읽고, 경로 끝이 null 이면 자리 없음이다")
    void readsJsonPointer() {
        RuleReading reading = read(
            """
            {"host": "oliveyoung.co.kr",
             "name": {"json": {"script": "script#__NEXT_DATA__", "pointer": "/props/product/name"}},
             "currentPrice": {"json": {"script": "script#__NEXT_DATA__", "pointer": "/props/product/price/sale"}},
             "originalPrice": {"json": {"script": "script#__NEXT_DATA__", "pointer": "/props/product/price/original"}}}""",
            """
            <html><body><script id="__NEXT_DATA__" type="application/json">
            {"props":{"product":{"name":"GANNI Loafer","price":{"sale":"16,800","original":null}}}}
            </script></body></html>"""
        );

        assertEquals("GANNI Loafer", reading.name().value());
        assertEquals(16_800, reading.currentPrice().value());
        assertEquals(State.ABSENT, reading.originalPrice().state());
    }

    @Test
    @DisplayName("스크립트가 없거나 JSON 이 깨졌거나 값의 타입이 다르면 자리 없음이 아니라 값 불량이다")
    void brokenJsonSourceIsInvalid() {
        String rule = """
            {"host": "oliveyoung.co.kr",
             "originalPrice": {"json": {"script": "script#__NEXT_DATA__", "pointer": "/price"}}}""";

        assertEquals(State.INVALID, read(rule, "<html><body><p>본문</p></body></html>").originalPrice().state());
        assertEquals(State.INVALID, read(rule, """
            <html><body><script id="__NEXT_DATA__" type="application/json">{"price": </script></body></html>""")
            .originalPrice().state());
        assertEquals(State.INVALID, read(rule, """
            <html><body><script id="__NEXT_DATA__" type="application/json">{"price": {"amount": 1000}}</script>
            </body></html>""").originalPrice().state());
        assertEquals(State.INVALID, read(rule, """
            <html><body><script id="__NEXT_DATA__" type="application/json">{"price": 1000.5}</script>
            </body></html>""").originalPrice().state());
    }

    @Test
    @DisplayName("이미지의 상대 주소는 문서의 최종 주소로 풀고 https 가 아니면 값 불량이다")
    void resolvesImageAgainstFinalUrl() {
        String rule = """
            {"host": "oliveyoung.co.kr", "imageUrl": {"css": {"selector": "img.main", "attribute": "src"}}}""";

        assertEquals(
            "https://www.oliveyoung.co.kr/img/a.jpg",
            read(rule, "<html><body><img class=\"main\" src=\"/img/a.jpg\"></body></html>").imageUrl().value()
        );
        assertEquals(
            State.INVALID,
            read(rule, "<html><body><img class=\"main\" src=\"http://cdn.example.com/a.jpg\"></body></html>")
                .imageUrl().state()
        );
        assertEquals(
            State.INVALID,
            read(rule, "<html><body><img class=\"main\"></body></html>").imageUrl().state()
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"id\": 1}",
        "{\"host\": \" \"}",
        "{\"marker\": \"\"}",
        "{\"host\": \"a.com\", \"name\": {}}",
        "{\"host\": \"a.com\", \"name\": {\"css\": {\"selector\": \"\"}}}",
        "{\"host\": \"a.com\", \"name\": {\"css\": {\"selector\": \"div[\"}}}",
        "{\"host\": \"a.com\", \"name\": {\"json\": {\"script\": \"script\", \"pointer\": \"\"}}}",
        "{\"host\": \"a.com\", \"name\": {\"json\": {\"script\": \"script\", \"pointer\": \"props/name\"}}}",
    })
    @DisplayName("대상이 없거나 출처가 비었거나 선택자·경로를 해석하지 못하는 규칙은 만들지 않는다")
    void rejectsUninterpretableRule(String json) {
        assertThrows(InvalidRuleException.class, () -> RuleFixtures.rule(json));
    }

    @Test
    @DisplayName("host 규칙은 같은 host 와 서브도메인에 맞고, 이름만 비슷한 다른 host 에는 맞지 않는다")
    void matchesHostAndSubdomains() {
        MallRule rule = RuleFixtures.rule("{\"host\": \"OliveYoung.co.kr.\"}");
        Document empty = document("<html></html>");

        assertTrue(rule.matches(ProductLink.parse("https://oliveyoung.co.kr/a"), empty));
        assertTrue(rule.matches(ProductLink.parse("https://m.oliveyoung.co.kr/a"), empty));
        assertFalse(rule.matches(ProductLink.parse("https://notoliveyoung.co.kr/a"), empty));
    }

    @Test
    @DisplayName("marker 규칙은 host 와 무관하게 문서에 표식 요소가 있을 때만 맞는다")
    void matchesMarkerInDocument() {
        MallRule rule = RuleFixtures.rule("{\"marker\": \"meta[name='generator'][content*='imweb']\"}");

        assertTrue(rule.matches(LINK, document(
            "<html><head><meta name=\"generator\" content=\"imweb v2\"></head></html>")));
        assertFalse(rule.matches(LINK, document("<html><head></head></html>")));
    }
}
