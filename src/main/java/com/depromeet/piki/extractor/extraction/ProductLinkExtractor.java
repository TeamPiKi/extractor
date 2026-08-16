package com.depromeet.piki.extractor.extraction;

import com.depromeet.piki.extractor.domain.ProductLink;
import com.depromeet.piki.extractor.domain.ProductSnapshot;

/** URL 추출의 공개 진입점 계약 — API 컨트롤러는 이 인터페이스만 알고, 전략 구성은 뒤에 숨는다. */
public interface ProductLinkExtractor {

    /**
     * @param authorized 이 대상이 플랫폼의 명시적 허락을 받았는가. 추출 경로 자체는 이 값과 무관하다 —
     *     정적 fetch 로 시작해 필요하면 브라우저로 승격하는 흐름은 항상 같다. 이 값이 여는 것은 렌더 서비스의
     *     우회 수단(지문 보정·프록시)뿐이며, 여기서는 판단하지 않고 그대로 전달만 한다. 허락 대상의 단일
     *     진실은 호출자(core)에 있고, 무상태인 이 서비스는 요청 단위로만 받는다. 누락은 false 로 정규화되는
     *     fail-safe 다({@code LinkExtractionRequest}).
     * @param model 호출자가 지정한 LLM 모델 힌트. authorized 와 같은 성질이다 — 정책의 단일 진실은 호출자 쪽
     *     동적 설정(DB, 백오피스)이고 무상태인 이 서비스는 요청 단위로만 받는다. null 이면 기본 모델을 쓰며,
     *     지정 모델이 사라졌으면 기본 모델로 대체된다.
     */
    ProductSnapshot extract(ProductLink link, boolean authorized, String model);
}
