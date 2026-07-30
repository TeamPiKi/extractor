package com.depromeet.piki.extractor.extraction;

import com.depromeet.piki.extractor.domain.ExtractionMethod;
import com.depromeet.piki.extractor.domain.ProductSnapshot;
import com.depromeet.piki.extractor.extraction.structured.StructuredDataExtractor;
import com.depromeet.piki.extractor.extraction.structured.StructuredExtraction;
import io.micrometer.core.instrument.MeterRegistry;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

// HTML(PageContent) → ProductSnapshot 의 공통 후반부: 구조화 데이터(JSON-LD/OpenGraph) 우선, 미달이면 같은
// HTML 을 Gemini 로 넘긴다(재fetch 없음). HTML 을 어디서 얻었는지(정적 fetch=plain, 헤드리스 렌더)와 무관한
// 파싱 파이프라인이라 두 전략(Default/HeadlessProductLinkExtractor)이 공유한다.
//
// 추출 방법을 product.extract 카운터로 집계한다 — via=structured(직접 파싱) 대 via=llm(LLM fallback) 의 비율이
// 곧 비싼 LLM 호출을 얼마나 줄였는지의 비용 지표다. fallback 은 reason 라벨로 사유(no_data/missing_field/invalid_value)를
// 분해해 "직접 파싱 적중률을 올리려면 어디를 보강할지"를 본다. application 태그는 management.metrics.tags 가 자동 부착한다.
@Component
public class HtmlSnapshotPipeline {

    private static final String EXTRACT_METRIC = "product.extract";
    private static final String TAG_VIA = "via";
    private static final String TAG_REASON = "reason";
    private static final String VIA_STRUCTURED = "structured";
    private static final String VIA_LLM = "llm";
    private static final String REASON_NONE = "none";

    private static final Logger log = LoggerFactory.getLogger(HtmlSnapshotPipeline.class);

    private final StructuredDataExtractor structuredDataExtractor;
    private final GeminiHtmlExtractor geminiHtmlExtractor;
    private final MeterRegistry meterRegistry;

    public HtmlSnapshotPipeline(
        StructuredDataExtractor structuredDataExtractor,
        GeminiHtmlExtractor geminiHtmlExtractor,
        MeterRegistry meterRegistry
    ) {
        this.structuredDataExtractor = structuredDataExtractor;
        this.geminiHtmlExtractor = geminiHtmlExtractor;
        this.meterRegistry = meterRegistry;
    }

    // timing 은 호출 전략이 채우는 로그 조각("fetch=123ms" / "render=5534ms") — HTML 획득 비용을 extract 원장
    // 로그 한 줄에 함께 남긴다. 전략 무관 파이프라인이 유일하게 전략을 아는 지점이라 문자열로만 받는다.
    public ProductSnapshot extract(PageContent page, String timing) {
        // HTML 을 한 번만 파싱해 구조화 파서와 Gemini fallback 이 같은 Document 를 공유한다(파싱·ld+json 식별 중복 제거).
        // baseUri 는 html 의 출처인 최종 URL(page.finalUrl) 기준 — redirect 를 따라갔으면 원본 link 와 host 가 다를 수 있다.
        Document document = Jsoup.parse(page.html(), page.finalUrl().value().toString());

        StructuredExtraction result = structuredDataExtractor.extract(document, page.link());

        // 카운터는 한 곳에서 항상 {via, reason} 두 키로 발행한다 — 경로마다 태그 키가 갈라지면 Prometheus 가
        // 같은 메트릭 이름의 뒤 시계열을 조용히 드롭하므로(라벨 키 집합 불일치), 키를 단일 지점에서 통일한다.
        // Miss 일 때 LLM 호출 전에 올려, LLM 이 실패해도 "직접 파싱으로 못 끝내 LLM 에 의존한 비율"에 포함되게 한다.
        // 전략(plain/headless) 라벨은 두지 않는다 — 헤드리스 볼륨의 관측은 escalation 메트릭·render 로그가 진다.
        String via = result instanceof StructuredExtraction.Extracted ? VIA_STRUCTURED : VIA_LLM;
        String reason = result instanceof StructuredExtraction.Miss miss ? miss.reason() : REASON_NONE;
        meterRegistry.counter(EXTRACT_METRIC, TAG_VIA, via, TAG_REASON, reason).increment();

        return switch (result) {
            case StructuredExtraction.Extracted extracted -> {
                log.info(
                    "extract via=structured {} html={}chars url={}",
                    timing,
                    page.html().length(),
                    page.link().safeLogString()
                );
                // 출처 표기는 값 생산자(파서·LLM)가 아니라 여기서 — finalUrl 을 아는 유일한 층이고,
                // 두 분기가 각자 method 를 확정하는 지점이라 표기가 갈라질 수 없다.
                yield extracted.snapshot().withOrigin(page.finalUrl(), ExtractionMethod.STRUCTURED);
            }
            case StructuredExtraction.Miss miss -> {
                long llmStart = System.nanoTime();
                ProductSnapshot snapshot = geminiHtmlExtractor.extract(document, page.link());
                long llmMs = (System.nanoTime() - llmStart) / 1_000_000;
                log.info(
                    "extract via=llm reason={} {} llm={}ms html={}chars url={}",
                    miss.reason(),
                    timing,
                    llmMs,
                    page.html().length(),
                    page.link().safeLogString()
                );
                yield snapshot.withOrigin(page.finalUrl(), ExtractionMethod.LLM);
            }
        };
    }
}
