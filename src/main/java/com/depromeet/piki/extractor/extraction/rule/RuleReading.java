package com.depromeet.piki.extractor.extraction.rule;

import java.util.ArrayList;
import java.util.List;

/**
 * 규칙 하나가 문서 한 장에서 읽은 것. 못 읽음을 자리 없음(ABSENT)과 값 불량(INVALID)으로 가르는 이유는
 * "할인 없음" 선언이 자리 없음에만 붙기 때문이다(TeamPiKi/infra contracts/extraction-api.md 7절).
 */
public record RuleReading(
    Field<String> name,
    Field<String> imageUrl,
    Field<Integer> currentPrice,
    Field<Integer> originalPrice
) {

    public enum State { UNSPECIFIED, READ, ABSENT, INVALID }

    public record Field<T>(State state, T value) {

        static <T> Field<T> unspecified() {
            return new Field<>(State.UNSPECIFIED, null);
        }

        static <T> Field<T> read(T value) {
            return new Field<>(State.READ, value);
        }

        static <T> Field<T> absent() {
            return new Field<>(State.ABSENT, null);
        }

        static <T> Field<T> invalid() {
            return new Field<>(State.INVALID, null);
        }

        public boolean isRead() {
            return state == State.READ;
        }

        boolean unread() {
            return state == State.ABSENT || state == State.INVALID;
        }
    }

    /** 읽기는 했지만 신뢰 검증(판매가 이상)에 걸린 정가는 값 불량이다. */
    RuleReading withOriginalPriceInvalid() {
        return new RuleReading(name, imageUrl, currentPrice, Field.invalid());
    }

    /** 이름·이미지·판매가 가운데 출처를 적고도 못 읽은 것이 있으면 몰의 구조가 바뀐 것으로 본다. */
    public boolean brokenRequiredField() {
        return name.unread() || imageUrl.unread() || currentPrice.unread();
    }

    /** 값은 계약(RuleProbeResult.unread_fields)의 표기. */
    public List<String> unreadFields() {
        List<String> unread = new ArrayList<>();
        if (name.unread()) {
            unread.add("name");
        }
        if (imageUrl.unread()) {
            unread.add("imageUrl");
        }
        if (currentPrice.unread()) {
            unread.add("currentPrice");
        }
        if (originalPrice.unread()) {
            unread.add("originalPrice");
        }
        return unread;
    }
}
