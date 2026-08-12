package com.depromeet.piki.extractor.extraction;

import com.depromeet.piki.extractor.domain.ProductLink;
import com.depromeet.piki.extractor.domain.ProductSnapshot;

/** URL 추출의 공개 진입점 계약 — API 컨트롤러는 이 인터페이스만 알고, 전략 구성은 뒤에 숨는다. */
public interface ProductLinkExtractor {

    /**
     * @param headlessFirst 호출자(core)의 플랫폼 라우팅 정책 힌트. 정책의 단일 진실은 호출자 쪽 동적 설정(DB,
     *     백오피스)에 있고, 무상태인 이 서비스는 요청 단위 힌트로만 받는다. true 면 plain(정적 fetch)을 건너뛰고
     *     처음부터 헤드리스로 추출한다 — 단 이 서비스의 헤드리스 스위치가 꺼져 있으면 무시된다. 힌트일 뿐이라
     *     headlessAllowed 가 false 면 함께 무시된다(허가가 라우팅보다 앞선다).
     * @param headlessAllowed 이 대상에 헤드리스를 써도 되는지에 대한 호출자의 허가. false 면 어떤 경로로도
     *     헤드리스를 타지 않는다 — 직행(headlessFirst)·차단 승격·불완전 승격 셋 다 닫힌다. 허가 대상의 단일
     *     진실은 호출자(core) 쪽에 있고, 무상태인 이 서비스는 요청 단위로만 받는다. 누락은 false 로 정규화되는
     *     fail-safe 다({@code LinkExtractionRequest}).
     * @param model 호출자가 지정한 LLM 모델 힌트. headlessFirst 와 같은 성질이다 — 정책의 단일 진실은 호출자 쪽
     *     동적 설정(DB, 백오피스)이고 무상태인 이 서비스는 요청 단위로만 받는다. null 이면 기본 모델을 쓰며,
     *     지정 모델이 사라졌으면 기본 모델로 대체된다.
     */
    ProductSnapshot extract(ProductLink link, boolean headlessFirst, boolean headlessAllowed, String model);
}
