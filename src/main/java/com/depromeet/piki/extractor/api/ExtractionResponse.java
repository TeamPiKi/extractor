package com.depromeet.piki.extractor.api;

import com.depromeet.piki.extractor.domain.ExtractionMethod;
import com.depromeet.piki.extractor.domain.ProductSnapshot;
import com.depromeet.piki.extractor.domain.ProductSnapshotException;
import lombok.extern.slf4j.Slf4j;

/**
 * 성공 응답 (docs/api-contract.md). 세 필드(name·imageUrl·currentPrice)는 호출자(core)의 READY 불변식과 같은
 * 조건이지만, **다 채우지 못해도 성공으로 내려보낸다** — 호출자가 부분값을 INCOMPLETE 로 받아 사용자가 나머지를
 * 채우기 때문이다(TeamPiKi/core#944). 하나도 못 건졌을 때만 확정 실패로 닫는다. currency 는 READY 필수가
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
        // 하나도 못 건졌을 때만 확정 실패로 닫는다. 예전에는 세 필드 중 하나라도 비면 닫아 채운 값까지 함께
        // 버렸는데, 사진에 가격이 박혀 있지 않은 것은 정상 입력이라 그 계약은 "쇼핑몰 화면 캡처"만 통과시켰다.
        // 부분값은 호출자가 INCOMPLETE 로 받아 사용자가 나머지를 채운다(TeamPiKi/core#944).
        if (snapshot.hasNoExtractedValue()) {
            throw ProductSnapshotException.untrustworthyValue();
        }
        // 부분값은 성공 응답이라 code 가 남지 않는다 — 어느 필드를 못 채웠는지는 여기서만 관측할 수 있다.
        if (snapshot.missingReadyField()) {
            log.info("extraction incomplete missing={} method={}", snapshot.missingFieldNames(), snapshot.method());
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
