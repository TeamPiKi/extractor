package com.depromeet.piki.extractor.support;

import com.depromeet.piki.extractor.domain.ProductLink;
import com.depromeet.piki.extractor.extraction.PageContent;
import com.depromeet.piki.extractor.extraction.headless.HeadlessRenderer;
import java.util.function.Function;

/**
 * 헤드리스 렌더 외부 경계를 통합 테스트에서 격리하는 stub. 통합 컨텍스트는 헤드리스 스위치가 꺼진 기본 구성이라
 * 정상 흐름에선 호출되지 않는다 — 설정이 바뀌어도 통합 테스트가 실제 렌더 서비스로 나가지 않게 하는 안전망이자,
 * 켜서 검증할 때의 주입 지점이다. default build 는 throw(명시 세팅 강제).
 */
public class StubHeadlessRenderer implements HeadlessRenderer {

    public Function<ProductLink, PageContent> build = link -> {
        throw new IllegalStateException("stub.build 를 테스트 본문에서 명시 세팅해야 한다. CLAUDE.md '테스트' 절 참고.");
    };

    @Override
    public PageContent render(ProductLink link) {
        return build.apply(link);
    }
}
