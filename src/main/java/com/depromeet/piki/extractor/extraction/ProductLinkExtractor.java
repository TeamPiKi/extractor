package com.depromeet.piki.extractor.extraction;

import com.depromeet.piki.extractor.domain.ProductLink;
import com.depromeet.piki.extractor.domain.ProductSnapshot;

// URL 추출의 공개 진입점 계약 —
// API 컨트롤러는 이 인터페이스만 알고, 전략 구성(plain/headless)은 뒤에 숨는다.
public interface ProductLinkExtractor {

    // headlessFirst: 호출자(PIKI-Server)의 플랫폼 라우팅 정책(HEADLESS_FIRST) 힌트. 정책의 단일 진실은 호출자
    // DB(백오피스 동적 설정)에 있고, 무상태인 이 서비스는 요청 단위 힌트로만 받는다. true 면 plain(정적 fetch)을
    // 건너뛰고 처음부터 헤드리스로 추출한다 — 단 product.extract.headless.enabled 가 꺼져 있으면 무시된다.
    ProductSnapshot extract(ProductLink link, boolean headlessFirst);
}
