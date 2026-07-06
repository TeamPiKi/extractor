package com.depromeet.piki.extractor.domain;

import com.depromeet.piki.extractor.common.exception.ExtractionErrorCode;
import com.depromeet.piki.extractor.common.exception.ExtractionException;

// PIKI-Server: product/domain/ProductLinkException.kt 포팅. 정상 흐름에선 호출자가 등록 경계에서 동기 검증을
// 이미 끝내고 보내므로 여기 닿지 않는다 — 다층 방어의 자기 경계 검증이며, 셋 다 INVALID_URL(확정 실패)로 답한다.
public final class ProductLinkException extends ExtractionException {

    private ProductLinkException(String message, Throwable cause) {
        super(message, ExtractionErrorCode.INVALID_URL, true, cause);
    }

    public static ProductLinkException blank() {
        return new ProductLinkException("링크를 입력해 주세요.", null);
    }

    // 원본 URL 은 message 에 박지 않는다 — 쿼리스트링/fragment 의 토큰·세션이 로그로 새는 경로가 되기 때문.
    // 디버깅 컨텍스트는 cause 로 연결해 stack trace 로만 남긴다.
    public static ProductLinkException invalidFormat(Throwable cause) {
        return new ProductLinkException("올바른 링크 형식이 아니에요. 다시 확인해 주세요.", cause);
    }

    public static ProductLinkException unsupportedScheme() {
        return new ProductLinkException("https 링크만 등록할 수 있어요.", null);
    }
}
