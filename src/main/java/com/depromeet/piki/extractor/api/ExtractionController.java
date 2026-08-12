package com.depromeet.piki.extractor.api;

import com.depromeet.piki.extractor.domain.ProductLink;
import com.depromeet.piki.extractor.domain.ProductSnapshot;
import com.depromeet.piki.extractor.extraction.ProductLinkExtractor;
import com.depromeet.piki.extractor.image.ImageExtractionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 내부 추출 API (docs/api-contract.md). 소비자는 core outbox 워커 하나뿐이고 보안그룹으로
 * 격리되므로 인증·응답 래퍼 없이 계약 그대로 노출한다.
 */
@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/internal/extractions")
public class ExtractionController {

    private final ProductLinkExtractor productLinkExtractor;
    private final ImageExtractionService imageExtractionService;

    @PostMapping("/link")
    public ExtractionResponse extractLink(
        @RequestBody LinkExtractionRequest request,
        @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId
    ) {
        // 정상 흐름이면 호출자가 등록 경계에서 이미 걸렀다 — 여기 parse 는 다층 방어다.
        ProductLink link = ProductLink.parse(request.url());
        // model 은 호출자가 백오피스에서 지정한 값이라 원장에 남긴다 — 추출 품질이 흔들릴 때 "그때 어느 모델이었나"를
        // 되짚는 유일한 근거다(자유 문자열이라 메트릭 라벨로는 못 쓴다).
        // headlessAllowed 도 원장에 남긴다 — 허가 없는 대상이 브라우저로 갔는지(또는 허가가 왜 안 왔는지)를
        // 사후에 되짚을 수 있는 유일한 근거다.
        log.info(
            "extract request correlationId={} headlessFirst={} headlessAllowed={} model={} url={}",
            correlationId,
            request.headlessFirst(),
            request.headlessAllowed(),
            request.model(),
            link.safeLogString()
        );
        ProductSnapshot snapshot = productLinkExtractor.extract(
            link,
            request.headlessFirst(),
            request.headlessAllowed(),
            request.model()
        );
        return ExtractionResponse.from(snapshot);
    }

    @PostMapping("/image")
    public ExtractionResponse extractImage(
        @RequestBody ImageExtractionRequest request,
        @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId
    ) {
        // bucket 은 내부 식별자라 URL 과 달리 마스킹 없이 로그에 남겨도 안전하다.
        log.info(
            "image extract request correlationId={} bucket={} key={} model={}",
            correlationId,
            request.bucket(),
            request.key(),
            request.model()
        );
        ProductSnapshot snapshot = imageExtractionService.extract(request.bucket(), request.key(), request.model());
        return ExtractionResponse.from(snapshot);
    }
}
