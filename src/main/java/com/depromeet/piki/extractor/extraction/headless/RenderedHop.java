package com.depromeet.piki.extractor.extraction.headless;

import com.depromeet.piki.extractor.domain.ProductLink;
import java.util.Map;
import java.util.Objects;

/**
 * 브라우저가 거쳐 간 문서 하나. body 는 서버 원문(3xx 는 빈값), dom 은 그 문서를 떠나기 직전(마지막 홉은 정착 후)의
 * 렌더 결과다. 어느 홉이 상품이고 차단인지는 소비자가 정한다.
 */
public record RenderedHop(
    ProductLink url,
    int status,
    Map<String, String> headers,
    String body,
    String dom
) {

    /** 빈값 정규화를 타입에 둔다 — wire 구현만이 아니라 stub·다른 구현자가 null 을 넣어도 소비자가 NPE 로 새지 않게. */
    public RenderedHop {
        Objects.requireNonNull(url, "url");
        headers = Objects.requireNonNullElse(headers, Map.of());
        body = Objects.requireNonNullElse(body, "");
        dom = Objects.requireNonNullElse(dom, "");
    }
}
