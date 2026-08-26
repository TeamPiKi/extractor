package com.depromeet.piki.extractor.extraction.http;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import javax.net.ssl.SSLException;
import org.apache.hc.client5.http.HttpRequestRetryStrategy;
import org.apache.hc.core5.http.ConnectionClosedException;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.Method;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.util.TimeValue;

/**
 * "요청이 대상 몰에 닿기 전"에 끊긴 경우에만 in-process 로 한 번 복구한다.
 *
 * <p>경계는 <b>대상 몰이 우리 요청을 봤는가</b> 하나다. 못 봤으면 몰 입장에서 아무 일도 없었으므로 다시 붙는 것이
 * 부작용도 추가 부하도 없다 — RFC 9110 §9.2.2 가 "응답을 읽기 전 통신 실패는 자동 반복해도 된다"고 명시하는
 * 부류이고, gRPC 는 이것을 transparent retry 라 부르며 재시도 횟수·예산에 <b>세지도 않는다</b>(gRFC A6).
 * 반대로 몰이 이미 받아서 거부(429/503)하거나 느린 경우는 몰이 일을 한 것이라, 되쏘면 그 자원을 한 번 더 쓴다.
 * 그쪽 재시도는 호출자(core)의 작업 큐가 단독으로 소유한다 — 거기에만 attempt 기록·상한·관측이 있다.
 *
 * <p>HttpClient5 기본 전략({@code DefaultHttpRequestRetryStrategy})을 그대로 쓸 수 없는 이유는 그것이 위 둘을
 * 섞기 때문이다: I/O 실패 복구와 함께 <b>429·503 응답까지 재시도</b>한다. 서버가 "과부하다"라고 말한 요청을
 * 클라이언트가 되쏘는 동작이라, 계층 중첩 재시도의 표준 경고에 정면으로 걸린다.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9110.html#section-9.2.2">RFC 9110 §9.2.2</a>
 * @see <a href="https://github.com/grpc/proposal/blob/master/A6-client-retries.md">gRPC gRFC A6</a>
 */
public class PreDeliveryRetryStrategy implements HttpRequestRetryStrategy {

    /** 복구는 딱 한 번. 즉시 재시도를 두 번 이상 하지 않는다는 것은 전송 오류 복구의 통상 상한이다. */
    private static final int MAX_RETRIES = 1;

    @Override
    public boolean retryRequest(HttpRequest request, IOException exception, int execCount, HttpContext context) {
        if (execCount > MAX_RETRIES) {
            return false;
        }
        // 멱등 메서드만 자동 반복한다(RFC 9110 §9.2.2). 페이지 fetch 는 GET 뿐이지만, 이 전략이 다른 호출에
        // 재사용될 때 그 전제가 조용히 깨지지 않게 여기서 막는다.
        if (!Method.isIdempotent(request.getMethod())) {
            return false;
        }
        return isPreDeliveryFailure(exception);
    }

    /**
     * 대상에 닿기 전 실패인가.
     *
     * <p>연결을 못 세운 경우(주소 해석·라우팅·TCP·TLS 실패)는 요청 자체가 나가지 않았지만 <b>재시도하지 않는다</b> —
     * 즉시 다시 시도해도 같은 결과가 나오는 결정론적 실패라, 남는 것은 지연뿐이다. 우리가 되살리려는 것은
     * <b>연결은 섰는데 그 위에서 끊긴</b> 경우다. 대표적으로 커넥션 풀에 남아 있던 연결을 몰이 유휴 타임아웃으로
     * 닫는 순간과 우리 요청 전송이 겹치는 경합인데, 이때 몰은 요청을 받지 못했고 다시 붙으면 대개 성공한다.
     */
    private boolean isPreDeliveryFailure(IOException exception) {
        if (exception instanceof UnknownHostException
            || exception instanceof ConnectException
            || exception instanceof NoRouteToHostException
            || exception instanceof SSLException) {
            return false;
        }
        // 읽기 타임아웃(InterruptedIOException)은 몰이 이미 요청을 받아 처리 중일 수 있어 "닿기 전"이 아니다.
        // 우리 예산을 한 번 더 태우기만 하므로 상위 큐로 넘긴다.
        if (exception instanceof java.io.InterruptedIOException) {
            return false;
        }
        return exception instanceof ConnectionClosedException
            || exception instanceof org.apache.hc.core5.http.NoHttpResponseException
            || exception instanceof java.net.SocketException;
    }

    /** 응답까지 받은 요청은 여기서 재시도하지 않는다 — 429·503 을 포함해 전부 상위 큐 소관이다. */
    @Override
    public boolean retryRequest(HttpResponse response, int execCount, HttpContext context) {
        return false;
    }

    /** 닿기 전 실패는 상대가 일을 한 적이 없어 백오프로 배려할 대상이 없다. 즉시 다시 붙는다. */
    @Override
    public TimeValue getRetryInterval(HttpResponse response, int execCount, HttpContext context) {
        return TimeValue.ZERO_MILLISECONDS;
    }
}
