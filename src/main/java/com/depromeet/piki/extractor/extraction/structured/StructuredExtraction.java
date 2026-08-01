package com.depromeet.piki.extractor.extraction.structured;

import com.depromeet.piki.extractor.domain.ProductSnapshot;

/**
 * 구조화 데이터(JSON-LD/OpenGraph) 파싱 결과. 실패(Miss)면 그 사유(reason)를 메트릭 라벨로 남긴 뒤
 * LLM fallback 으로 넘어간다. reason 은 "직접 파싱 적중률을 올리려면 어디를 보강할지" 판단의 단서다:
 * <ul>
 *   <li>no_data — 구조화 데이터 자체가 없음. 사이트가 미제공이라 LLM 이 불가피하다.</li>
 *   <li>missing_field — 데이터는 있으나 필수 필드가 없음. 파서가 더 많은 위치를 봐야 할 수 있다.</li>
 *   <li>invalid_value — 값은 있으나 검증·범위를 통과 못 함. 정규화 보강 여지가 있다.</li>
 * </ul>
 *
 * <p>rank 는 "데이터에 근접한 정도"(클수록 근접)다. 여러 후보의 사유를 합칠 때 이 rank 로 더 근접한 쪽을 고른다.
 * enum 선언 순서(ordinal)가 아니라 명시 rank 로 비교해, 상수 재배치·중간 추가에도 비교가 깨지지 않는다.
 */
public sealed interface StructuredExtraction permits StructuredExtraction.Extracted, StructuredExtraction.Miss {

    record Extracted(ProductSnapshot snapshot) implements StructuredExtraction {
    }

    enum Miss implements StructuredExtraction {
        NO_DATA("no_data", 0),
        MISSING_FIELD("missing_field", 1),
        INVALID_VALUE("invalid_value", 2);

        private final String reason;
        private final int rank;

        Miss(String reason, int rank) {
            this.reason = reason;
            this.rank = rank;
        }

        public String reason() {
            return reason;
        }

        public int rank() {
            return rank;
        }
    }
}
