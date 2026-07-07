package com.depromeet.piki.extractor.extraction;

import com.depromeet.piki.extractor.domain.ProductLink;
import com.depromeet.piki.extractor.domain.ProductSnapshot;
import com.depromeet.piki.extractor.extraction.gemini.GeminiHttpClient;
import com.depromeet.piki.extractor.extraction.gemini.GeminiProperties;
import com.depromeet.piki.extractor.extraction.http.FetchProperties;
import com.depromeet.piki.extractor.extraction.http.HttpPageFetcher;
import com.depromeet.piki.extractor.extraction.http.PageFetchHttpClientConfig;
import com.depromeet.piki.extractor.extraction.http.RequestScopedDnsResolver;
import com.depromeet.piki.extractor.extraction.structured.StructuredDataExtractor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.ObjectMapper;

// 실제 Gemini API + 외부 쇼핑몰 fetch 를 호출하는 E2E 측정 테스트.
// 비용·외부 의존성·quota 가 발생하므로 기본은 @Disabled. 측정이 필요할 때만 명시적으로 enable.
// GEMINI_API_KEY 가 OS env 에 있다고 가정한다.
@Disabled("실제 Gemini API 호출 + 외부 쇼핑몰 fetch. 측정 필요 시 수동으로 enable 후 실행.")
class ProductLinkExtractE2ETest {

    private static final int ITERATIONS = 3;

    // SSR / CSR / JSON-LD 보유 / 보유 안 함 등 다양한 패턴으로 섞었음.
    private static final List<String> URLS = List.of(
        "https://www.musinsa.com/products/1551840",
        "https://www.musinsa.com/products/6029415",
        "https://www.29cm.co.kr/products/3915051",
        "https://www.29cm.co.kr/products/1437795"
    );

    private final RequestScopedDnsResolver dnsResolver = new RequestScopedDnsResolver();
    private final FetchProperties fetchProperties = FetchProperties.defaults();
    private final HttpPageFetcher pageFetcher = new HttpPageFetcher(
        new PageFetchHttpClientConfig().pageFetchRestClient(ObservationRegistry.NOOP, dnsResolver, fetchProperties),
        dnsResolver,
        fetchProperties
    );
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final GeminiProperties properties = new GeminiProperties(
        System.getenv("GEMINI_API_KEY"),
        "gemini-3-flash-preview",
        new GeminiProperties.Retry()
    );
    private final GeminiHttpClient httpClient = new GeminiHttpClient(properties, objectMapper, ObservationRegistry.NOOP);

    // fetch → 구조화 우선 → Gemini fallback 전체 경로를 측정한다 (오케스트레이터 그대로).
    private final DefaultProductLinkExtractor extractor = new DefaultProductLinkExtractor(
        pageFetcher,
        new HtmlSnapshotPipeline(
            new StructuredDataExtractor(objectMapper),
            new GeminiHtmlExtractor(httpClient),
            new SimpleMeterRegistry()
        )
    );

    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    @DisplayName("여러 URL 을 N회씩 호출해 latency 및 추출 결과를 누적 측정한다")
    void measureLatencyAndResults() {
        List<Outcome> results = new ArrayList<>();
        for (String rawUrl : URLS) {
            for (int i = 0; i < ITERATIONS; i++) {
                ProductLink link = ProductLink.parse(rawUrl);
                long started = System.nanoTime();
                Outcome outcome;
                try {
                    ProductSnapshot product = extractor.extract(link);
                    long ms = (System.nanoTime() - started) / 1_000_000;
                    outcome = new Outcome(rawUrl, i + 1, "OK", product.name(), product.currentPrice(), ms, null);
                } catch (Exception e) {
                    long ms = (System.nanoTime() - started) / 1_000_000;
                    outcome = new Outcome(
                        rawUrl, i + 1, "FAIL", null, null, ms,
                        e.getClass().getSimpleName() + ": " + e.getMessage()
                    );
                }
                results.add(outcome);
                System.out.println(formatLine(outcome));
            }
        }
        printSummary(results);
    }

    private String formatLine(Outcome o) {
        if ("OK".equals(o.status())) {
            return "[" + o.attempt() + "/" + ITERATIONS + "] " + o.elapsedMs() + "ms  " + o.url()
                + "  name=" + o.name() + "  price=" + o.currentPrice();
        }
        return "[" + o.attempt() + "/" + ITERATIONS + "] " + o.elapsedMs() + "ms  " + o.url() + "  FAIL  " + o.error();
    }

    private void printSummary(List<Outcome> results) {
        System.out.println("\n=== summary ===");
        Map<String, List<Outcome>> byUrl = new LinkedHashMap<>();
        for (Outcome o : results) {
            byUrl.computeIfAbsent(o.url(), k -> new ArrayList<>()).add(o);
        }
        byUrl.forEach((url, rs) -> {
            long ok = rs.stream().filter(o -> "OK".equals(o.status())).count();
            int avg = (int) rs.stream().mapToLong(Outcome::elapsedMs).average().orElse(0);
            List<Integer> prices = rs.stream()
                .filter(o -> "OK".equals(o.status()))
                .map(Outcome::currentPrice)
                .distinct()
                .toList();
            System.out.println(url + ": " + ok + "/" + rs.size() + " OK  avg=" + avg + "ms  uniquePrice=" + prices);
        });
    }

    private record Outcome(
        String url,
        int attempt,
        String status,
        String name,
        Integer currentPrice,
        long elapsedMs,
        String error
    ) {
    }
}
