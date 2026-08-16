package com.depromeet.piki.extractor.extraction.http;

import com.depromeet.piki.extractor.common.exception.ExtractionErrorCode;
import com.depromeet.piki.extractor.common.exception.ExtractionException;
import java.util.Objects;

/**
 * fetch 실패의 계약 예외. permanent(확정/일시) 축에 더해 escalatable(헤드리스 에스컬레이션 대상 여부, #657) 축을
 * 가진다. permanent 가 응답 status 로 어떻게 번역되는지는 docs/api-contract.md 가 정본이다.
 */
public final class PageFetchException extends ExtractionException {

    /** 접근 실패는 어느 단계든 같은 안내라 한 상수를 공유한다(단계 구분은 호출 지점 로그로). 응답엔 code 만 나간다. */
    private static final String LINK_UNREACHABLE = "링크에 접근하지 못했어요. 주소를 다시 확인해 주세요.";

    /**
     * 정적 fetch 실패를 실제 브라우저(헤드리스)로 재시도(escalate)할지 표시한다. FallbackProductLinkExtractor 가
     * 이 값으로 정한다(에스컬레이션 축은 호출자의 outbox 재시도 축과 직교). 정책은 "무조건 폴백": 예외는 둘뿐이고
     * 나머지 fetch 실패는 전부 escalatable 이다 — 봇 방어가 어떤 status 로도 위장해 status·body 로 차단/genuine 을
     * 못 가른다.
     *
     * <p>false 인 둘은 서로 다른 이유로 그렇다. blockedHost 는 보안 판단(내부망에 브라우저를 겨누는 것 자체가 SSRF)이고,
     * unresolvableHost 는 성립 불가 판단(주소가 없으면 브라우저도 갈 곳이 없다)이다. 앞은 recall 을 포기한 것이지만
     * 뒤는 포기할 recall 자체가 없다.
     *
     * <p>기본 false(fail-closed): 각 팩토리가 명시적으로 true 를 줘야 escalate 된다.
     */
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

    /**
     * 대상 페이지 서버의 일시 가능 5xx(게이트웨이 오류·과부하·타임아웃) 또는 연결 실패. 일시 실패로 두고 호출자
     * recover 가 재시도한다. escalatable=true(무조건 폴백): 헤드리스는 같은 서버를 때려 일시 오류엔 이득이 없을
     * 수 있으나, 그 낭비는 escalation 메트릭(category)으로 조사한다.
     */
    public static PageFetchException upstreamError(Throwable cause) {
        return new PageFetchException(LINK_UNREACHABLE, ExtractionErrorCode.UPSTREAM_ERROR, false, cause, true);
    }

    /**
     * host 를 IP 로 조회하지 못한 경우(InternalHostGuard 의 DNS 조회 실패). 없는 주소는 몇 번을 다시 물어도 없으므로
     * 확정 실패로 둔다 — 일시로 두면 호출자가 재시도 예산을 다 태운 뒤 "외부가 불안정했다"로 종결해, 실제 사유(등록된
     * 주소가 잘못됐다)를 운영 지표에서 가린다. code 는 형식 위반과 같은 INVALID_URL 이다: 두 경우 모두 결론이
     * "이 주소로는 갈 수 없다"라 호출자가 달리 행동할 여지가 없다.
     *
     * <p>escalatable=false — resolve 되지 않는 host 는 헤드리스 브라우저도 같은 이유로 도달하지 못한다.
     *
     * <p>{@code UnknownHostException} 은 NXDOMAIN 과 리졸버 일시 장애(SERVFAIL·타임아웃)를 구분하지 않아 후자도
     * 확정 실패가 된다. 그래도 확정으로 두는 이유: 리졸버 장애는 이 박스 전역의 문제라 개별 추출의 재시도가 아니라
     * 호스트 관측이 다룰 일이고, 그 창에서 확정된 건은 사용자 재등록이 새 시도를 만든다. RCODE 로 정확히 가르려면
     * SSRF 가드·IP pin 계약(RequestScopedDnsResolver)까지 바꿔야 해서 별건이다.
     */
    public static PageFetchException unresolvableHost(Throwable cause) {
        return new PageFetchException(LINK_UNREACHABLE, ExtractionErrorCode.INVALID_URL, true, cause, false);
    }

    /**
     * 대상 서버가 결정론적 재실패로 보는 5xx(HttpPageFetcher 의 PERMANENT_SERVER_ERRORS)를 준 경우. 우리가 fetch 하는
     * 대형 몰은 사실상 상시 가용이라 대개 진짜 장애가 아니라 봇 방어다 — 확정 실패로 보고 escalatable=true(헤드리스면
     * 뚫릴 수 있다). body 유무로 봇차단/장애를 나누지 않는다: 봇 방어가 body(캡차·차단 페이지)를 실을 수 있어
     * 구분이 불확실하다.
     */
    public static PageFetchException permanentUpstreamError(Throwable cause) {
        return new PageFetchException(LINK_UNREACHABLE, ExtractionErrorCode.PERMANENT_UPSTREAM, true, cause, true);
    }

    /**
     * 4xx (403·404·410·429 등). 입력 URL 문제로 보는 확정 실패이나, 봇 방어가 404("없는 척")·403(차단)·
     * 429(throttle)로 클로킹할 수 있어 escalatable=true — 실제 브라우저면 뚫릴 수 있다.
     */
    public static PageFetchException clientError(Throwable cause) {
        return new PageFetchException(LINK_UNREACHABLE, ExtractionErrorCode.FETCH_CLIENT_ERROR, true, cause, true);
    }

    /** 응답 body 가 빈 경우. 일시적일 수 있어 일시 실패이되, 봇이 빈 응답으로 막는 것일 수도 있어 escalatable=true. */
    public static PageFetchException emptyBody() {
        return new PageFetchException("해당 링크에서 정보를 가져오지 못했어요.", ExtractionErrorCode.UPSTREAM_ERROR, false, null, true);
    }

    /**
     * fetch 는 2xx 였지만 본문이 데이터 없는 CSR 셸이라 파싱이 no-data 로 끝난 경우(EmptyShellDetector 판정,
     * cause 가 원래의 파싱 실패). 정적 fetch 는 몇 번을 받아도 같은 셸이라 확정 실패이되, 실제 브라우저는 JS 를
     * 실행해 콘텐츠를 그리므로 escalatable=true — "200 이지만 빈 셸" 이 기존 에스컬레이션 축(fetch 예외)의
     * 사각이던 것을 닫는다(카카오 톡딜 실측).
     */
    public static PageFetchException emptyShell(Throwable cause) {
        // 다른 팩토리와 달리 cause 가 필수다 — 재분류 예외라 원래의 파싱 실패 없이 만들어질 수 없다.
        Objects.requireNonNull(cause, "cause");
        return new PageFetchException("해당 링크에서 정보를 가져오지 못했어요.", ExtractionErrorCode.EMPTY_SHELL, true, cause, true);
    }

    /**
     * redirect 가 hop 상한을 넘어 무한·체인 의심. 대상 페이지의 고정된 비정상 상태라 확정 실패.
     * redirect 기반 차단(챌린지로 튕김)일 수 있고 헤드리스는 redirect 를 네이티브로 다루므로 escalatable=true.
     */
    public static PageFetchException tooManyRedirects() {
        return new PageFetchException(LINK_UNREACHABLE, ExtractionErrorCode.TOO_MANY_REDIRECTS, true, null, true);
    }

    /** 대상 서버가 3xx 를 주면서 Location 이 없거나 깨진 값을 준 비정상 redirect. 확정 실패, escalatable=true. */
    public static PageFetchException malformedRedirect(Throwable cause) {
        return new PageFetchException(LINK_UNREACHABLE, ExtractionErrorCode.MALFORMED_REDIRECT, true, cause, true);
    }

    /**
     * host 가 사설/메타데이터/loopback 영역으로 resolve 될 때의 SSRF 차단 신호. escalatable=false — 내부망에
     * 헤드리스를 겨누는 것 자체가 SSRF 취약점이라 "무조건 폴백"의 유일한 예외다(recall 트레이드오프가 아니라 보안).
     */
    public static PageFetchException blockedHost() {
        return new PageFetchException("등록할 수 없는 링크예요.", ExtractionErrorCode.BLOCKED_HOST, true, null, false);
    }
}
