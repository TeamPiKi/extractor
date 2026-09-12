package com.depromeet.piki.extractor.api;

import com.depromeet.piki.contracts.extraction.v1.ExtractionResult;
import com.depromeet.piki.contracts.extraction.v1.LinkExtractionRequest;
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
 * 내부 추출 API (docs/api-contract.md). 소비자는 core 의 파싱 작업 큐 워커 하나뿐이고 보안그룹으로
 * 격리되므로 인증·응답 래퍼 없이 계약 그대로 노출한다.
 *
 * <p>link 경로의 요청·응답 타입은 계약 정본에서 생성된다. image·probe 는 아직 Jackson record 다(파일럿 범위 밖).
 */
@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/internal/extractions")
public class ExtractionController {

    private final ProductLinkExtractor productLinkExtractor;
    private final ImageExtractionService imageExtractionService;

    @PostMapping("/link")
    public ExtractionResult extractLink(
        @RequestBody LinkExtractionRequest request,
        @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId
    ) {
        // 정상 흐름이면 호출자가 등록 경계에서 이미 걸렀다 — 여기 parse 는 다층 방어다.
        ProductLink link = ProductLink.parse(request.getUrl());
        // 생략된 문자열 필드는 빈 문자열로 읽힌다(proto3). 추출기 계약은 "null 이면 기본 모델"이라 여기서 맞춘다.
        String model = request.getModel().isEmpty() ? null : request.getModel();
        // model 은 호출자가 백오피스에서 지정한 값이라 원장에 남긴다 — 추출 품질이 흔들릴 때 "그때 어느 모델이었나"를
        // 되짚는 유일한 근거다(자유 문자열이라 메트릭 라벨로는 못 쓴다).
        // authorized 도 원장에 남긴다 — 우회 수단이 열린 요청이었는지를 사후에 되짚을 수 있는 유일한 근거다.
        log.info(
            "extract request correlationId={} authorized={} model={} url={}",
            correlationId,
            request.getAuthorized(),
            model,
            link.safeLogString()
        );
        ProductSnapshot snapshot = productLinkExtractor.extract(link, request.getAuthorized(), model);
        return ExtractionResultMapper.from(snapshot);
    }

    @PostMapping("/image")
    public ExtractionResult extractImage(
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
        return ExtractionResultMapper.from(snapshot);
    }
}
