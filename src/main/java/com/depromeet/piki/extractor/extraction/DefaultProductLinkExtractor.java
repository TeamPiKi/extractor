package com.depromeet.piki.extractor.extraction;

import com.depromeet.piki.extractor.domain.ProductLink;
import com.depromeet.piki.extractor.domain.ProductSnapshot;
import com.depromeet.piki.extractor.extraction.structured.StructuredDataExtractor;
import com.depromeet.piki.extractor.extraction.structured.StructuredExtraction;
import io.micrometer.core.instrument.MeterRegistry;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

// PIKI-Server: product/service/DefaultProductLinkExtractor.kt 포팅.
// 정적 HTTP fetch 기반 추출 "전략"(LinkExtractionStrategy)이다. fetch 를 1회 수행하고, 구조화 데이터(JSON-LD/OpenGraph)로
// 먼저 파싱한다. 필수 필드가 검증을 통과하면 LLM 을 건너뛰고, 미달이면 같은 HTML 을 Gemini 로 넘겨 추출한다(재fetch 없음).
// 공개 진입점은 FallbackProductLinkExtractor(ProductLinkExtractor 구현)이고, 이 빈은 그 "plain" 전략으로 주입된다.
//
// 추출 방법을 product.extract 카운터로 집계한다 — via=structured(직접 파싱) 대 via=llm(LLM fallback) 의 비율이
// 곧 비싼 LLM 호출을 얼마나 줄였는지의 비용 지표다. fallback 은 reason 라벨로 사유(no_data/missing_field/invalid_value)를
// 분해해 "직접 파싱 적중률을 올리려면 어디를 보강할지"를 본다. application 태그는 management.metrics.tags 가 자동 부착한다.
@Component(LinkExtractionStrategy.PLAIN)
public class DefaultProductLinkExtractor implements LinkExtractionStrategy {

    private static final String EXTRACT_METRIC = "product.extract";
    private static final String TAG_VIA = "via";
    private static final String TAG_REASON = "reason";
    private static final String VIA_STRUCTURED = "structured";
    private static final String VIA_LLM = "llm";
    private static final String REASON_NONE = "none";

    private static final Logger log = LoggerFactory.getLogger(DefaultProductLinkExtractor.class);

    private final PageFetcher pageFetcher;
    private final StructuredDataExtractor structuredDataExtractor;
    private final GeminiHtmlExtractor geminiHtmlExtractor;
    private final MeterRegistry meterRegistry;

    public DefaultProductLinkExtractor(
        PageFetcher pageFetcher,
        StructuredDataExtractor structuredDataExtractor,
        GeminiHtmlExtractor geminiHtmlExtractor,
        MeterRegistry meterRegistry
    ) {
        this.pageFetcher = pageFetcher;
        this.structuredDataExtractor = structuredDataExtractor;
        this.geminiHtmlExtractor = geminiHtmlExtractor;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public ProductSnapshot extract(ProductLink link) {
        long fetchStart = System.nanoTime();
        PageContent page = pageFetcher.fetch(link);
        long fetchMs = (System.nanoTime() - fetchStart) / 1_000_000;

        // HTML 을 한 번만 파싱해 구조화 파서와 Gemini fallback 이 같은 Document 를 공유한다(파싱·ld+json 식별 중복 제거).
        // baseUri 는 html 의 출처인 최종 URL(page.finalUrl) 기준 — redirect 를 따라갔으면 원본 link 와 host 가 다를 수 있다.
        Document document = Jsoup.parse(page.html(), page.finalUrl().value().toString());

        StructuredExtraction result = structuredDataExtractor.extract(document, link);

        // 카운터는 한 곳에서 항상 {via, reason} 두 키로 발행한다 — 경로마다 태그 키가 갈라지면 Prometheus 가
        // 같은 메트릭 이름의 뒤 시계열을 조용히 드롭하므로(라벨 키 집합 불일치), 키를 단일 지점에서 통일한다.
        // Miss 일 때 LLM 호출 전에 올려, LLM 이 실패해도 "직접 파싱으로 못 끝내 LLM 에 의존한 비율"에 포함되게 한다.
        String via = result instanceof StructuredExtraction.Extracted ? VIA_STRUCTURED : VIA_LLM;
        String reason = result instanceof StructuredExtraction.Miss miss ? miss.reason() : REASON_NONE;
        meterRegistry.counter(EXTRACT_METRIC, TAG_VIA, via, TAG_REASON, reason).increment();

        return switch (result) {
            case StructuredExtraction.Extracted extracted -> {
                log.info(
                    "extract via=structured fetch={}ms html={}chars url={}",
                    fetchMs,
                    page.html().length(),
                    link.safeLogString()
                );
                yield extracted.snapshot();
            }
            case StructuredExtraction.Miss miss -> {
                long llmStart = System.nanoTime();
                ProductSnapshot snapshot = geminiHtmlExtractor.extract(document, link);
                long llmMs = (System.nanoTime() - llmStart) / 1_000_000;
                log.info(
                    "extract via=llm reason={} fetch={}ms llm={}ms html={}chars url={}",
                    miss.reason(),
                    fetchMs,
                    llmMs,
                    page.html().length(),
                    link.safeLogString()
                );
                yield snapshot;
            }
        };
    }
}
