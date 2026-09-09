package com.depromeet.piki.extractor.extraction.headless;

import com.depromeet.piki.extractor.common.exception.ExtractionErrorCode;
import com.depromeet.piki.extractor.common.exception.ExtractionException;

/** 헤드리스 렌더 실패의 계약 번역 (docs/api-contract.md). message 는 로그·디버깅용 — 응답 body 엔 code 만 나간다. */
public final class HeadlessRenderException extends ExtractionException {

    private HeadlessRenderException(String message, ExtractionErrorCode code, boolean permanent, Throwable cause) {
        super(message, code, permanent, cause);
    }

    /**
     * 실제 브라우저로도 모든 홉이 차단 신호다(HeadlessProductLinkExtractor 의 분류). 차단 신호에 429·"잠시 후 다시" 같은 일시
     * 신호가 섞여 영구/일시를 못 가르므로, fail-safe 원칙(분류 불가 실패는 일시)대로 일시 실패로 둔다.
     * 결정론적 차단의 재시도 낭비는 호출자의 bounded 재시도가 바운드한다(docs/api-contract.md).
     *
     * <p>code 를 HEADLESS_UPSTREAM 과 분리해 두는 이유: "차단" 과 "렌더 서비스 장애" 는 관측·대응이 다르다
     * (차단 추세 = UNSUPPORTED 정책 후보, 장애 추세 = 렌더 박스 점검).
     */
    public static HeadlessRenderException blocked() {
        return new HeadlessRenderException(
            "헤드리스 렌더의 모든 홉이 차단 신호다 — 일시 챌린지가 섞여 있어 일시 실패로 분류한다.",
            ExtractionErrorCode.HEADLESS_BLOCKED,
            false,
            null
        );
    }

    /**
     * 렌더 서비스 쪽 실패(연결·타임아웃·비-2xx·홉 없음) 일괄 번역. 일시적일 수 있어 일시 실패로 두고, 호출자
     * recover 의 bounded 재시도가 흡수한다(docs/api-contract.md).
     */
    public static HeadlessRenderException upstream(String detail, Throwable cause) {
        return new HeadlessRenderException(
            "헤드리스 렌더에 실패했다: " + detail,
            ExtractionErrorCode.HEADLESS_UPSTREAM,
            false,
            cause
        );
    }
}
