package com.depromeet.piki.extractor.extraction;

import com.depromeet.piki.extractor.domain.ProductLink;
import com.depromeet.piki.extractor.domain.ProductSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

// PIKI-Server: product/service/HeadlessProductLinkExtractor.kt 포팅.
// 차단 우회 헤드리스 추출 "전략"의 자리(seam). 정적 HTTP fetch 가 봇 차단에 막히는 플랫폼을, 실제 브라우저를
// 띄우는 별도 서비스(PIKI-HeadlessBrowser 의 POST /render)로 뚫는 경로다. 이관 7단계에서 구현한다 —
// 렌더된 HTML 이면 기존 파서/LLM 에 흘려넣고, 구조화 응답이면 직접 매핑한다. 어느 쪽이든 이 전략은 결과를
// ProductSnapshot 으로 돌려주므로 진입점 계약은 그대로다.
//
// 지금은 미구현 placeholder 다. FallbackProductLinkExtractor 가 product.extract.headless.enabled=false 로 이
// 경로를 꺼 두므로 정상 흐름에선 절대 호출되지 않는다. enabled 를 켠 채 여기 닿았다면 "seam 은 열렸으나 구현이
// 없다"는 설정 실수다. IllegalStateException 은 계약 예외가 아니므로 호출자에게 일시 실패(재시도 후 FAILED)로
// 떨어진다 — 그 오설정이 조용히 묻히지 않도록 매 호출마다 error 로그로 명시 신호를 남긴다(정상 흐름엔 0건).
@Component(LinkExtractionStrategy.HEADLESS)
public class HeadlessProductLinkExtractor implements LinkExtractionStrategy {

    private static final Logger log = LoggerFactory.getLogger(HeadlessProductLinkExtractor.class);

    @Override
    public ProductSnapshot extract(ProductLink link) {
        log.error(
            "headless 추출이 호출됐으나 미구현이다 — product.extract.headless.enabled 를 구현 없이 켠 설정 실수. url={}",
            link.safeLogString()
        );
        throw new IllegalStateException(
            "헤드리스 추출 전략은 아직 구현되지 않았다. product.extract.headless.enabled 를 켜기 전에 구현을 먼저 붙여야 한다."
        );
    }
}
