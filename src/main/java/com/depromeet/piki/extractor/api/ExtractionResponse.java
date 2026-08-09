package com.depromeet.piki.extractor.api;

import com.depromeet.piki.extractor.domain.ExtractionMethod;
import com.depromeet.piki.extractor.domain.ProductSnapshot;
import com.depromeet.piki.extractor.domain.ProductSnapshotException;
import lombok.extern.slf4j.Slf4j;

/**
 * 성공 응답 (docs/api-contract.md). {@code from} 이 요구하는 세 필드는 호출자(core)의 READY 불변식과 같은
 * 조건이다 — 그래서 하나라도 못 채우면 성공으로 내려보내지 않고 실패로 떨어뜨린다. currency 는 READY 필수가
 * 아니라 nullable 이다.
 *
 * <p>finalUrl·method 는 additive 확장이다. finalUrl 은 리다이렉트 귀결점(link 경로 항상, image 경로 null)으로
 * core 의 상품 정체성(canonical) 정규화 입력이고, method 는 core 가 snapshot 출처를 구분 저장하는 근거다.
 */
@Slf4j
public record ExtractionResponse(
    String name,
    String imageUrl,
    Integer currentPrice,
    String currency,
    String finalUrl,
    ExtractionMethod method
) {

    public static ExtractionResponse from(ProductSnapshot snapshot) {
        // 출처 미표기는 값 전달을 막을 사유가 아니라(호출자는 출처 미기록으로 저장) 관측으로만 남긴다 —
        // 현재 두 경로(파이프라인·이미지)는 항상 withOrigin 을 거치므로, 이 경고는 미래의 새 경로가
        // 표기를 빠뜨렸다는 트립와이어다.
        if (snapshot.method() == null) {
            log.warn("extraction response without method - origin marking missed");
        }
        if (snapshot.missingReadyField()) {
            throw ProductSnapshotException.untrustworthyValue();
        }
        return new ExtractionResponse(
            snapshot.name(),
            snapshot.imageUrl(),
            snapshot.currentPrice(),
            snapshot.currency(),
            snapshot.finalUrl() == null ? null : snapshot.finalUrl().value().toString(),
            snapshot.method()
        );
    }
}
