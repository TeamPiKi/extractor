package com.depromeet.piki.extractor.api;

import com.depromeet.piki.extractor.domain.ProductLink;
import com.depromeet.piki.extractor.domain.ProductSnapshot;
import com.depromeet.piki.extractor.extraction.ProductLinkExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 내부 추출 API (docs/api-contract.md). 소비자는 PIKI-Server outbox 워커 하나뿐이고 보안그룹으로 격리되므로
// 인증·응답 래퍼 없이 계약 그대로 노출한다. 이미지 경로(/internal/extractions/image)는 이관 6단계에서 추가한다.
@RestController
@RequestMapping("/internal/extractions")
public class ExtractionController {

    private static final Logger log = LoggerFactory.getLogger(ExtractionController.class);

    private final ProductLinkExtractor productLinkExtractor;

    public ExtractionController(ProductLinkExtractor productLinkExtractor) {
        this.productLinkExtractor = productLinkExtractor;
    }

    @PostMapping("/link")
    public LinkExtractionResponse extractLink(
        @RequestBody LinkExtractionRequest request,
        // 호출자의 item_snapshot id. 로그·trace 상관용이며 동작에 영향 없다 (계약 §2).
        @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId
    ) {
        // 형식·스킴 위반은 ProductLink.parse 가 INVALID_URL(422) 로 떨어뜨린다 — 정상 흐름에선 호출자가
        // 등록 경계에서 이미 걸렀으므로 방어 검증이다(다층 방어).
        ProductLink link = ProductLink.parse(request.url());
        log.info("extract request correlationId={} url={}", correlationId, link.safeLogString());
        ProductSnapshot snapshot = productLinkExtractor.extract(link);
        return LinkExtractionResponse.from(snapshot);
    }
}
