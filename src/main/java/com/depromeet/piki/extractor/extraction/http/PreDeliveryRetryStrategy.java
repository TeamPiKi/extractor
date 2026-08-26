package com.depromeet.piki.extractor.extraction.http;

import java.io.IOException;
import org.apache.hc.client5.http.HttpRequestRetryStrategy;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.Method;
import org.apache.hc.core5.http.NoHttpResponseException;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.util.TimeValue;

/**
 * "요청이 대상 몰에 닿기 전"에 끊긴 경우에만 in-process 로 한 번 복구한다.
 *
 * <p>경계는 <b>대상 몰이 우리 요청을 봤는가</b> 하나다. 못 봤으면 몰 입장에서 아무 일도 없었으므로 다시 붙는 것이
 * 부작용도 추가 부하도 없다. RFC 9110 §9.2.2 가 "응답을 읽기 전 통신 실패는 자동 반복해도 된다"고 명시하는
 * 부류이고, gRPC 는 이것을 transparent retry 라 부르며 재시도 횟수·예산에 <b>세지도 않는다</b>(gRFC A6).
 * 반대로 몰이 이미 받아서 거부(429/503)하거나 느린 경우는 몰이 일을 한 것이라, 되쏘면 그 자원을 한 번 더 쓴다.
 * 그쪽 재시도는 호출자(core)의 작업 큐가 단독으로 소유한다. 거기에만 attempt 기록·상한·관측이 있다.
 *
 * <p>복구 대상은 {@link NoHttpResponseException} <b>하나뿐</b>이다. 요청은 나갔는데 응답 바이트를 하나도 받지
 * 못한 채 연결이 닫혔다는 뜻이라, "몰이 못 봤다"를 예외 이름만으로 판정할 수 있는 유일한 경우다. 실전에서는
 * 커넥션 풀의 유휴 검증(PageFetchHttpClientConfig 의 validateAfterInactivity)이 못 거른 찰나의 keep-alive
 * 경합이 이 모양으로 온다.
 *
 * <p><b>판정 규칙: 예외 이름이 "몰이 봤나"를 단독으로 판정하지 못하면 복구 대상이 아니다. 모호성은 재시도로
 * 덮지 않고 예방(풀의 유휴 검증)으로 없앤다.</b> 이 규칙으로 제외한 것들:
 *
 * <ul>
 *   <li>{@code SocketException}(리셋): 유휴 경합의 리셋일 수도, 차단측이 능동적으로 보낸 RST 일 수도 있다.
 *       전자는 풀 검증이 이미 예방하고, 후자는 다시 붙어도 같은 결과인데 비용이 유계가 아니다(실제 사고에서
 *       몰측이 33초를 잡아둔 뒤 리셋했고, 이를 재시도해 24초를 더 태웠다).
 *   <li>{@code ConnectionClosedException}: 응답 도중 끊김을 포함해 몰이 일한 뒤일 수 있다. HttpClient5 기본
 *       전략조차 이것은 재시도하지 않는다.
 *   <li>연결 수립 실패(DNS·TCP·TLS): 요청 자체가 안 나갔지만, 즉시 다시 시도해도 같은 결과가 나오는 결정론적
 *       실패라 남는 것은 지연뿐이다.
 *   <li>읽기 타임아웃({@code InterruptedIOException}): 몰이 이미 받아 처리 중일 수 있다.
 * </ul>
 *
 * <p>HttpClient5 기본 전략({@code DefaultHttpRequestRetryStrategy})을 그대로 쓸 수 없는 이유도 같은 규칙이다.
 * 기본값은 429·503 응답과 {@code SocketException} 까지 재시도해, 몰이 봤거나 봤는지 모르는 요청을 몰의 의사와
 * 무관하게 되쏜다.
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
        return exception instanceof NoHttpResponseException;
    }

    /** 응답까지 받은 요청은 여기서 재시도하지 않는다. 429·503 을 포함해 전부 상위 큐 소관이다. */
    @Override
    public boolean retryRequest(HttpResponse response, int execCount, HttpContext context) {
        return false;
    }

    /** 복구 대상은 몰이 일을 한 적이 없는 경우라 백오프로 배려할 대상이 없다. 즉시 다시 붙는다. */
    @Override
    public TimeValue getRetryInterval(HttpResponse response, int execCount, HttpContext context) {
        return TimeValue.ZERO_MILLISECONDS;
    }
}
