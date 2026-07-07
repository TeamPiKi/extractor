package com.depromeet.piki.extractor.extraction.http;

import com.depromeet.piki.extractor.common.exception.ExtractionErrorCode;
import com.depromeet.piki.extractor.common.exception.ExtractionException;

// fetch 실패의 계약 예외. permanent(일시 502 vs 확정 422) 축에 더해 escalatable(헤드리스 에스컬레이션 대상 여부, #657) 축을 가진다.
public final class PageFetchException extends ExtractionException {

    // 접근 실패는 어느 단계 실패든 같은 안내라 한 상수를 공유한다(단계 구분은 호출 지점 로그로). 로그 전용 — 응답엔 code 만 나간다.
    private static final String LINK_UNREACHABLE = "링크에 접근하지 못했어요. 주소를 다시 확인해 주세요.";

    // 정적 fetch 실패를 실제 브라우저(헤드리스)로 재시도(escalate)할지 표시한다. FallbackProductLinkExtractor 가
    // 이 값으로 정한다(에스컬레이션 축은 호출자의 outbox 재시도 축과 직교). 정책은 "무조건 폴백": SSRF(blockedHost, 보안)만
    // 빼고 모든 fetch 실패가 escalatable 이다 — 봇 방어가 어떤 status 로도 위장해 status·body 로 차단/genuine 을 못 가른다.
    // 기본 false(fail-closed): 각 팩토리가 명시적으로 true 를 줘야 escalate 되고, SSRF 만 default false 를 유지한다.
    private final boolean escalatable;

    private PageFetchException(
        String message,
        ExtractionErrorCode code,
        boolean permanent,
        Throwable cause,
        boolean escalatable
    ) {
        super(message, code, permanent, cause);
        this.escalatable = escalatable;
    }

    public boolean escalatable() {
        return escalatable;
    }

    // 대상 페이지 서버가 502/503/504(게이트웨이 오류·과부하·타임아웃) 또는 연결 실패. 일시적일 수 있어 일시 실패(502)
    // — 호출자 recover 가 재시도한다. escalatable=true (무조건 폴백): 헤드리스는 같은 서버를 때려 일시 오류엔 이득이
    // 없을 수 있으나, 그 낭비는 escalation 메트릭(category)으로 조사한다.
    public static PageFetchException upstreamError(Throwable cause) {
        return new PageFetchException(LINK_UNREACHABLE, ExtractionErrorCode.UPSTREAM_ERROR, false, cause, true);
    }

    // 대상 서버가 500/501 을 준 경우. 우리가 fetch 하는 대형 몰은 사실상 상시 가용이라 대개 진짜 장애가 아니라 봇 방어다.
    // 재시도해도 결정론적으로 재실패하는 확정 실패(422)로 보고, escalatable=true(헤드리스면 뚫릴 수 있다).
    // body 유무로 봇차단/장애를 나누지 않는다 — 봇 방어가 body(캡차·차단 페이지)를 실을 수 있어 구분이 불확실하다.
    public static PageFetchException permanentUpstreamError(Throwable cause) {
        return new PageFetchException(LINK_UNREACHABLE, ExtractionErrorCode.PERMANENT_UPSTREAM, true, cause, true);
    }

    // 4xx (403·404·410·429 등). 입력 URL 문제로 보는 확정 실패(422)이나, 봇 방어가 404("없는 척")·403(차단)·
    // 429(throttle)로 클로킹할 수 있어 escalatable=true — 실제 브라우저면 뚫릴 수 있다.
    public static PageFetchException clientError(Throwable cause) {
        return new PageFetchException(LINK_UNREACHABLE, ExtractionErrorCode.FETCH_CLIENT_ERROR, true, cause, true);
    }

    // 응답 body 가 빈 경우. 일시적일 수 있어 일시 실패(502)이되, 봇이 빈 응답으로 막는 것일 수도 있어 escalatable=true.
    public static PageFetchException emptyBody() {
        return new PageFetchException("해당 링크에서 정보를 가져오지 못했어요.", ExtractionErrorCode.UPSTREAM_ERROR, false, null, true);
    }

    // redirect 가 hop 상한을 넘어 무한·체인 의심. 대상 페이지의 고정된 비정상 상태라 확정 실패(422).
    // redirect 기반 차단(챌린지로 튕김)일 수 있고 헤드리스는 redirect 를 네이티브로 다루므로 escalatable=true.
    public static PageFetchException tooManyRedirects() {
        return new PageFetchException(LINK_UNREACHABLE, ExtractionErrorCode.TOO_MANY_REDIRECTS, true, null, true);
    }

    // 대상 서버가 3xx 를 주면서 Location 이 없거나 깨진 값을 준 비정상 redirect. 확정 실패(422), escalatable=true.
    public static PageFetchException malformedRedirect(Throwable cause) {
        return new PageFetchException(LINK_UNREACHABLE, ExtractionErrorCode.MALFORMED_REDIRECT, true, cause, true);
    }

    // host 가 사설/메타데이터/loopback 영역으로 resolve 될 때의 SSRF 차단 신호. escalatable=false — 내부망에
    // 헤드리스를 겨누는 것 자체가 SSRF 취약점이라 "무조건 폴백"의 유일한 예외다(recall 트레이드오프가 아니라 보안).
    public static PageFetchException blockedHost() {
        return new PageFetchException("등록할 수 없는 링크예요.", ExtractionErrorCode.BLOCKED_HOST, true, null, false);
    }
}
