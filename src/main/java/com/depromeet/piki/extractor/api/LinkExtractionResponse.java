package com.depromeet.piki.extractor.api;

import com.depromeet.piki.extractor.domain.ProductSnapshot;
import com.depromeet.piki.extractor.domain.ProductSnapshotException;

// 성공(200) 응답. 계약(docs/api-contract.md): name·currentPrice·imageUrl 의 non-null 을 이 서비스가 보장한다 —
// 호출자(PIKI-Server)의 READY 불변식(name·price·imageUrl·extractedAt, extractedAt 은 호출자가 전이 시점에 채움)과
// 동일 조건이며, 보장 못 하면 성공이 아니라 422(UNTRUSTWORTHY_VALUE)다. 원본에선 이 미달이 워커의 불변식 예외
// 흡수(→FAILED)로 처리됐다 — 여기서 422 로 앞당겨 떨어뜨려도 최종 전이는 동일하게 FAILED 다.
// currency 는 READY 필수가 아니라 nullable 그대로 내린다.
public record LinkExtractionResponse(
    String name,
    String imageUrl,
    Integer currentPrice,
    String currency
) {

    public static LinkExtractionResponse from(ProductSnapshot snapshot) {
        if (snapshot.name() == null || snapshot.name().isBlank()
            || snapshot.imageUrl() == null
            || snapshot.currentPrice() == null) {
            throw ProductSnapshotException.untrustworthyValue();
        }
        return new LinkExtractionResponse(
            snapshot.name(),
            snapshot.imageUrl(),
            snapshot.currentPrice(),
            snapshot.currency()
        );
    }
}
