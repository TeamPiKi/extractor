package com.depromeet.piki.extractor.api;

import com.depromeet.piki.contracts.extraction.v1.ExtractionMethod;
import com.depromeet.piki.contracts.extraction.v1.ExtractionResult;
import com.depromeet.piki.extractor.domain.ProductSnapshot;
import com.depromeet.piki.extractor.domain.ProductSnapshotException;
import lombok.extern.slf4j.Slf4j;

/**
 * 도메인 결과를 성공 응답(계약 정본 {@code extraction.proto} 의 {@code ExtractionResult})으로 옮긴다. 세 필드
 * (name·imageUrl·currentPrice)는 호출자(core)의 READY 불변식과 같은 조건이지만, **다 채우지 못해도 성공으로
 * 내려보낸다** — 호출자가 부분값을 INCOMPLETE 로 받아 사용자가 나머지를 채우기 때문이다(TeamPiKi/core#944).
 * 하나도 못 건졌을 때만 확정 실패로 닫는다.
 *
 * <p>응답 클래스는 계약에서 생성되므로 여기서 필드를 정의하지 않는다. 값이 없는 필드는 setter 를 부르지 않아
 * JSON 에서 생략된다(호출자는 생략과 null 을 같게 읽는다).
 */
@Slf4j
public final class ExtractionResultMapper {

    private ExtractionResultMapper() {
    }

    public static ExtractionResult from(ProductSnapshot snapshot) {
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
            // 도메인 enum 과 계약 enum 은 이름이 같다 — 어긋나면 여기서 IllegalArgumentException 으로 500 이 나
            // 호출자의 일시 실패 재시도로 떨어지고, 원인은 계약 대조 테스트가 먼저 잡는다.
            result.setMethod(ExtractionMethod.valueOf(snapshot.method().name()));
        }
        return result.build();
    }
}
