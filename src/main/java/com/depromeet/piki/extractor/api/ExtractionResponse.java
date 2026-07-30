package com.depromeet.piki.extractor.api;

import com.depromeet.piki.extractor.domain.ExtractionMethod;
import com.depromeet.piki.extractor.domain.ProductSnapshot;
import com.depromeet.piki.extractor.domain.ProductSnapshotException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// 성공(200) 응답. link·image 두 엔드포인트가 같은 모양을 공유한다(둘 다 ProductSnapshot 을 내려보낸다).
// 계약(docs/api-contract.md): name(non-blank)·currentPrice·imageUrl 의 non-null 을 이 서비스가 보장한다 —
// 호출자(core)의 READY 불변식(name·price·imageUrl·extractedAt, extractedAt 은 호출자가 전이 시점에 채움)과
// 동일 조건이며, 보장 못 하면 성공이 아니라 422(UNTRUSTWORTHY_VALUE)다. currency 는 READY 필수가 아니라 nullable.
// finalUrl·method 는 additive 확장(계약 §2) — finalUrl 은 리다이렉트 귀결점(link 경로 항상, image 경로 null)으로
// core 의 정체성 canonical 정규화 입력이고, method(STRUCTURED|LLM)는 core 의 snapshot 출처(SERVER/SERVER_LLM) 근거다.
public record ExtractionResponse(
    String name,
    String imageUrl,
    Integer currentPrice,
    String currency,
    String finalUrl,
    ExtractionMethod method
) {

    private static final Logger log = LoggerFactory.getLogger(ExtractionResponse.class);

    public static ExtractionResponse from(ProductSnapshot snapshot) {
        // 출처 미표기는 값 전달을 막을 사유가 아니라(호출자는 출처 미기록으로 저장) 관측으로만 남긴다 —
        // 현재 두 경로(파이프라인·이미지)는 항상 withOrigin 을 거치므로, 이 경고는 미래의 새 경로가
        // 표기를 빠뜨렸다는 트립와이어다.
        if (snapshot.method() == null) {
            log.warn("extraction response without method - origin marking missed");
        }
        if (snapshot.name() == null || snapshot.name().isBlank()
            || snapshot.imageUrl() == null
            || snapshot.currentPrice() == null) {
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
