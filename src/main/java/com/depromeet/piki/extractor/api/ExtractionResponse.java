package com.depromeet.piki.extractor.api;

import com.depromeet.piki.extractor.domain.ProductSnapshot;
import com.depromeet.piki.extractor.domain.ProductSnapshotException;

/**
 * 성공 응답 (docs/api-contract.md). {@code from} 이 요구하는 세 필드는 호출자(PIKI-Server)의 READY
 * 불변식과 같은 조건이다 — 그래서 하나라도 못 채우면 성공으로 내려보내지 않고 실패로 떨어뜨린다.
 * currency 는 READY 필수가 아니라 nullable 이다.
 */
public record ExtractionResponse(
    String name,
    String imageUrl,
    Integer currentPrice,
    String currency
) {

    public static ExtractionResponse from(ProductSnapshot snapshot) {
        if (snapshot.name() == null || snapshot.name().isBlank()
            || snapshot.imageUrl() == null
            || snapshot.currentPrice() == null) {
            throw ProductSnapshotException.untrustworthyValue();
        }
        return new ExtractionResponse(
            snapshot.name(),
            snapshot.imageUrl(),
            snapshot.currentPrice(),
            snapshot.currency()
        );
    }
}
