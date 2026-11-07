package com.depromeet.piki.extractor.extraction;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.nodes.Document;

/**
 * 가시 텍스트에 그 금액이 실제로 보이는가. LLM 입력(sanitize)은 JS 상태 script 를 지우므로, 정가가 거기에만 있고 본문엔
 * 판매가만 남은 페이지에서 LLM 은 "할인 표시 없음 → 정가 = 판매가" 로 답한다. 판매가가 본문에 보이지도 않는 그 답은
 * 근거 없는 단정이라 받지 않는다. 할인 중인 상품이 0% 로 굳는 것이 모름(null)보다 나쁘다.
 */
final class VisiblePrices {

    /** 천 단위 구분자가 있는 수와 없는 수를 따로 잡는다 — 숫자 스트림으로 뭉치면 "1,295,000" 안의 "9500" 이 오탐된다. */
    private static final Pattern AMOUNT = Pattern.compile("\\d{1,3}(?:,\\d{3})+|\\d+");

    private VisiblePrices() {
    }

    static boolean contains(Document document, int amount) {
        Objects.requireNonNull(document, "document");
        String target = String.valueOf(amount);
        Matcher matcher = AMOUNT.matcher(document.body().text());
        while (matcher.find()) {
            if (matcher.group().replace(",", "").equals(target)) {
                return true;
            }
        }
        return false;
    }
}
