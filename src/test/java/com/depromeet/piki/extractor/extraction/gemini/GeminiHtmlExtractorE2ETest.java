package com.depromeet.piki.extractor.extraction.gemini;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.depromeet.piki.extractor.domain.ProductLink;
import com.depromeet.piki.extractor.domain.ProductSnapshot;
import com.depromeet.piki.extractor.extraction.GeminiHtmlExtractor;
import io.micrometer.observation.ObservationRegistry;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.json.JsonMapper;

/**
 * 실제 Gemini API 를 호출하는 생존성 테스트.
 *
 * 비용·외부 의존성이 있으므로 기본은 @Disabled. 호출 경로(인증·스키마·직렬화·모델)가 살아 있는지
 * 확인이 필요할 때만 명시적으로 enable. GEMINI_API_KEY 가 환경에 있다고 가정한다.
 *
 * 포팅 노트: 원본은 정적 fetch 레이어(HttpPageFetcher)로 HTML 을 가져왔으나, 이 레이어는 아직 이관되지 않았다.
 * 이 E2E 의 목적은 "Gemini 호출 자체의 생존성"이라, fetch 레이어 이관 전까지는 표준 JDK HttpClient 로 대체한다.
 * fetch 레이어 이관 후에는 HttpPageFetcher 로 되돌려 원본과 완전히 일치시키는 것이 좋다.
 */
@Disabled("실제 Gemini API 호출. 검증 필요 시 수동으로 enable 후 실행.")
class GeminiHtmlExtractorE2ETest {

    private final JsonMapper objectMapper = JsonMapper.builder().build();
    private final GeminiProperties properties = new GeminiProperties(System.getenv("GEMINI_API_KEY"));
    private final GeminiHttpClient httpClient = new GeminiHttpClient(properties, objectMapper, ObservationRegistry.NOOP);
    private final GeminiHtmlExtractor extractor = new GeminiHtmlExtractor(httpClient);

    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    @DisplayName("Gemini end-to-end 호출이 살아 있고 응답을 구조화해 돌려준다")
    void geminiEndToEndIsAlive() throws Exception {
        // 호출 경로(인증·스키마·직렬화·모델)가 살아 있는지 확인하는 생존성 테스트.
        // 어떤 종류의 실패도 회귀 신호로 간주해 fail 시킨다.
        ProductLink link = ProductLink.parse("https://www.apple.com/shop/buy-iphone");

        String html = fetchHtml(link);
        Document document = Jsoup.parse(html, link.value().toString());
        ProductSnapshot product = extractor.extract(document, link);

        assertNotNull(product.name(), "Gemini 가 상품명을 추출했어야 한다");
    }

    private String fetchHtml(ProductLink link) throws Exception {
        HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
        HttpRequest request = HttpRequest.newBuilder(link.value())
            .header("User-Agent", "Mozilla/5.0")
            .GET()
            .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString()).body();
    }
}
