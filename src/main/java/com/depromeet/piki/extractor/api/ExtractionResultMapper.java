package com.depromeet.piki.extractor.api;

import com.depromeet.piki.contracts.extraction.v1.ExtractionMethod;
import com.depromeet.piki.contracts.extraction.v1.ExtractionResult;
import com.depromeet.piki.extractor.domain.ProductSnapshot;
import com.depromeet.piki.extractor.domain.ProductSnapshotException;
import lombok.extern.slf4j.Slf4j;

/**
 * 도메인 결과를 성공 응답으로 옮긴다. 부분값도 성공으로 내려보내는 이유는 호출자가 INCOMPLETE 로 받아
 * 사용자가 나머지를 채우기 때문이다(TeamPiKi/core#944).
 */
@Slf4j
public final class ExtractionResultMapper {

    private ExtractionResultMapper() {
    }

    public static ExtractionResult from(ProductSnapshot snapshot) {
        // 두 경로 모두 withOrigin 을 거치므로 이 경고는 새 경로가 표기를 빠뜨렸다는 트립와이어다.
        if (snapshot.method() == null) {
            log.warn("extraction response without method - origin marking missed");
        }
        if (snapshot.hasNoExtractedValue()) {
            throw ProductSnapshotException.untrustworthyValue();
        }
        // 부분값은 성공 응답이라 code 가 남지 않는다 — 어느 필드를 못 채웠는지는 여기서만 관측할 수 있다.
        if (snapshot.missingReadyField()) {
            log.info("extraction incomplete missing={} method={}", snapshot.missingFieldNames(), snapshot.method());
        }
        ExtractionResult.Builder result = ExtractionResult.newBuilder();
        if (snapshot.name() != null) {
            result.setName(snapshot.name());
        }
        if (snapshot.imageUrl() != null) {
            result.setImageUrl(snapshot.imageUrl());
        }
        if (snapshot.currentPrice() != null) {
            result.setCurrentPrice(snapshot.currentPrice());
        }
        if (snapshot.currency() != null) {
            result.setCurrency(snapshot.currency());
        }
        if (snapshot.finalUrl() != null) {
            result.setFinalUrl(snapshot.finalUrl().value().toString());
        }
        if (snapshot.method() != null) {
            // 이름이 어긋나면 여기서 터진다 - 계약 대조 테스트가 먼저 잡는다.
            result.setMethod(ExtractionMethod.valueOf(snapshot.method().name()));
        }
        return result.build();
    }
}
