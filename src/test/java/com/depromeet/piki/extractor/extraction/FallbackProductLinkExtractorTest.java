package com.depromeet.piki.extractor.extraction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.depromeet.piki.extractor.domain.ProductLink;
import com.depromeet.piki.extractor.domain.ProductSnapshot;
import com.depromeet.piki.extractor.domain.ProductSnapshotException;
import com.depromeet.piki.extractor.extraction.http.PageFetchException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Fallback(진입점)이 "plain 먼저, 못 끝내면(차단·불완전 결과) headless" 를 flag·escalatable·READY 필수 필드
 * 규칙대로 엮는지 Spring 없이 검증한다.
 *
 * <p>통합 테스트는 외부 경계(PageFetcher·GeminiClient)만 stub 하고 이 라우팅을 실제로 타므로, 분기 망라는
 * 여기 단위에서 한다. 두 전략은 실제 빈이 네트워크/브라우저를 요구해 단위로 세울 수 없어,
 * LinkExtractionStrategy fake 로 "전략의 결과"만 주입한다.
 *
 * <p>authorized 는 헤드리스 진입 조건이 아니다 — 승격 경로는 그 값과 무관하게 같고, 이 플래그는 렌더 경계로
 * 전달만 된다. 전달이 끊기지 않는지는 아래 authorized* 케이스가 못박는다.
 */
class FallbackProductLinkExtractorTest {

    private final ProductLink link = ProductLink.parse("https://shop.example.com/p");
    // plain "성공" 픽스처는 READY 필수 필드(name·imageUrl·currentPrice)를 다 채운다 — 비면 불완전 승격 분기로 빠진다.
    private final ProductSnapshot snapshot =
        new ProductSnapshot(null, "나이키", "https://cdn.example.com/nike.png", 99_000, null);
    private final ProductSnapshot incomplete = new ProductSnapshot(null, "톡딜 상품", "https://cdn.example.com/p.png", null, null);

    private static class FakeStrategy implements LinkExtractionStrategy {
        private final Function<ProductLink, ProductSnapshot> fn;
        int calls = 0;

        FakeStrategy(Function<ProductLink, ProductSnapshot> fn) {
            this.fn = fn;
        }

        Boolean lastAuthorized;

        @Override
        public ProductSnapshot extract(ProductLink link, boolean authorized, String model) {
            calls++;
            lastAuthorized = authorized;
            return fn.apply(link);
        }
    }

    private FallbackProductLinkExtractor fallback(
        boolean headlessEnabled,
        FakeStrategy plain,
        FakeStrategy headless,
        MeterRegistry meterRegistry
    ) {
        return new FallbackProductLinkExtractor(
            plain,
            headless,
            meterRegistry,
            HeadlessExtractionProperties.of(headlessEnabled)
        );
    }

    private FallbackProductLinkExtractor fallback(boolean headlessEnabled, FakeStrategy plain, FakeStrategy headless) {
        return fallback(headlessEnabled, plain, headless, new SimpleMeterRegistry());
    }

    @Test
    @DisplayName("headless 가 꺼져 있으면 plain 결과를 그대로 반환하고 headless 는 호출하지 않는다")
    void headlessOffDelegatesToPlain() {
        FakeStrategy plain = new FakeStrategy(l -> snapshot);
        FakeStrategy headless = new FakeStrategy(l -> {
            throw new IllegalStateException("headless 는 호출되면 안 됨");
        });

        ProductSnapshot result = fallback(false, plain, headless).extract(link, false, null);

        assertEquals(snapshot, result);
        assertEquals(1, plain.calls);
        assertEquals(0, headless.calls);
    }

    @Test
    @DisplayName("headless 가 꺼져 있으면 escalatable 차단이어도 plain 예외를 그대로 전파한다")
    void headlessOffPropagatesEscalatableFailure() {
        FakeStrategy plain = new FakeStrategy(l -> {
            throw PageFetchException.clientError(new RuntimeException("403"));
        });
        FakeStrategy headless = new FakeStrategy(l -> snapshot);

        assertThrows(PageFetchException.class, () -> fallback(false, plain, headless).extract(link, false, null));
        assertEquals(0, headless.calls);
    }

    @Test
    @DisplayName("headless 가 켜져 있고 plain 이 escalatable 차단으로 막히면 headless 로 에스컬레이트하고 success 로 집계한다")
    void escalatesToHeadlessOnEscalatableFailure() {
        FakeStrategy plain = new FakeStrategy(l -> {
            throw PageFetchException.clientError(new RuntimeException("403"));
        });
        ProductSnapshot headlessSnapshot =
            new ProductSnapshot(null, "헤드리스 결과", "https://cdn.example.com/h.png", 50_000, null);
        FakeStrategy headless = new FakeStrategy(l -> headlessSnapshot);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        ProductSnapshot result = fallback(true, plain, headless, registry).extract(link, false, null);

        assertEquals(headlessSnapshot, result);
        assertEquals(1, plain.calls);
        assertEquals(1, headless.calls);
        // outcome·category 로 escalate 를 쪼개야 어떤 차단 사유가 폴백을 유발하는지 조사할 수 있다.
        assertEquals(
            1.0,
            registry.counter("product.extract.escalation", "outcome", "success", "category", "FETCH_CLIENT_ERROR").count()
        );
    }

    @Test
    @DisplayName("headless 가 켜져 있어도 plain 이 성공하면 headless 는 호출하지 않는다")
    void headlessOnButPlainSucceeds() {
        FakeStrategy plain = new FakeStrategy(l -> snapshot);
        FakeStrategy headless = new FakeStrategy(l -> {
            throw new IllegalStateException("headless 는 호출되면 안 됨");
        });

        ProductSnapshot result = fallback(true, plain, headless).extract(link, false, null);

        assertEquals(snapshot, result);
        assertEquals(1, plain.calls);
        assertEquals(0, headless.calls);
    }

    @Test
    @DisplayName("headless 가 Error 로 실패해도 failed 로 집계하고 전파한다")
    void headlessErrorIsCountedAndPropagated() {
        // headless 가 Error 계열로 실패할 때(미구현·OOM 등) catch 가 Exception 으로 좁으면 outcome=failed 집계가
        // 조용히 빠진다 — 관측이 사라지는 이 함정을 고정한다.
        FakeStrategy plain = new FakeStrategy(l -> {
            throw PageFetchException.clientError(new RuntimeException("403"));
        });
        FakeStrategy headless = new FakeStrategy(l -> {
            throw new Error("headless 미구현");
        });
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        assertThrows(Error.class, () -> fallback(true, plain, headless, registry).extract(link, false, null));
        assertEquals(
            1.0,
            registry.counter("product.extract.escalation", "outcome", "failed", "category", "FETCH_CLIENT_ERROR").count()
        );
    }

    @Test
    @DisplayName("headless 도 실패하면 그 예외를 전파하고 escalation 을 failed 로 집계한다")
    void headlessFailureIsCountedAndPropagated() {
        // 호출자(core 워커)의 재시도 판정과 맞물리는 지점이라, headless 예외가 그대로 상위로 전파돼야 한다.
        // "폴백했는데도 못 가져온" 이 outcome=failed 로 집계돼, 무조건-폴백의 낭비·한계를 관측할 수 있어야 한다.
        FakeStrategy plain = new FakeStrategy(l -> {
            throw PageFetchException.clientError(new RuntimeException("403"));
        });
        FakeStrategy headless = new FakeStrategy(l -> {
            throw new RuntimeException("headless 도 실패");
        });
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        assertThrows(RuntimeException.class, () -> fallback(true, plain, headless, registry).extract(link, false, null));
        assertEquals(1, plain.calls);
        assertEquals(1, headless.calls);
        assertEquals(
            1.0,
            registry.counter("product.extract.escalation", "outcome", "failed", "category", "FETCH_CLIENT_ERROR").count()
        );
    }




    @Test
    @DisplayName("headless 가 켜져 있어도 escalatable 이 아닌 실패는 전파하고 headless 를 호출하지 않는다")
    void nonEscalatableFailureIsNeverEscalated() {
        // SSRF 로 우리가 막은 내부망(blockedHost)은 escalatable=false — 절대 헤드리스로 넘기지 않는다("무조건 폴백"의 유일 예외).
        FakeStrategy plain = new FakeStrategy(l -> {
            throw PageFetchException.blockedHost();
        });
        FakeStrategy headless = new FakeStrategy(l -> snapshot);

        assertThrows(PageFetchException.class, () -> fallback(true, plain, headless).extract(link, false, null));
        assertEquals(0, headless.calls);
    }

    @Test
    @DisplayName("plain 이 성공해도 READY 필수 필드가 비면 headless 로 에스컬레이트하고 INCOMPLETE_SNAPSHOT 으로 집계한다")
    void escalatesToHeadlessOnIncompleteSnapshot() {
        // 부분 SSR SPA(카카오 톡딜): fetch 는 성공하고 이름·이미지는 있지만 가격이 문서에 없다. 이대로 끝내면
        // 응답 경계에서 확정 실패가 되므로, 확정 전에 브라우저 DOM 으로 한 번 더 시도해야 HEADLESS_FIRST 정책이
        // 정합성 조건이 아니라 최적화로 남는다.
        FakeStrategy plain = new FakeStrategy(l -> incomplete);
        ProductSnapshot headlessSnapshot =
            new ProductSnapshot(null, "톡딜 상품", "https://cdn.example.com/p.png", 23_900, null);
        FakeStrategy headless = new FakeStrategy(l -> headlessSnapshot);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        ProductSnapshot result = fallback(true, plain, headless, registry).extract(link, false, null);

        assertEquals(headlessSnapshot, result);
        assertEquals(1, plain.calls);
        assertEquals(1, headless.calls);
        assertEquals(
            1.0,
            registry.counter("product.extract.escalation", "outcome", "success", "category", "INCOMPLETE_SNAPSHOT").count()
        );
    }

    @Test
    @DisplayName("불완전 승격의 headless 결과가 여전히 불완전하면 재승격 없이 그대로 반환하고 failed 로 집계한다")
    void incompleteEscalationReturnsHeadlessResultAsIs() {
        // 확정 실패 판정은 응답 경계 한 곳이 진다 — 여기서 또 승격하면 루프 축이 생긴다. 다만 outcome 은
        // "요청을 살렸는가"라, 예외 없이 반환됐어도 불완전(경계에서 확정 실패 예정)이면 success 로 세지 않는다.
        FakeStrategy plain = new FakeStrategy(l -> incomplete);
        FakeStrategy headless = new FakeStrategy(l -> incomplete);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        ProductSnapshot result = fallback(true, plain, headless, registry).extract(link, false, null);

        assertEquals(incomplete, result);
        assertEquals(1, headless.calls);
        assertEquals(
            1.0,
            registry.counter("product.extract.escalation", "outcome", "failed", "category", "INCOMPLETE_SNAPSHOT").count()
        );
    }


    @Test
    @DisplayName("불완전 승격에서 headless 가 escalatable 실패를 던져도 재진입 없이 전파하고 failed 로 집계한다")
    void incompleteEscalationFailurePropagatesWithoutReentry() {
        // headless 실패가 plain 의 catch 로 새면 headless 를 두 번 때린다 — 승격은 시도 1회로 바운드된다.
        FakeStrategy plain = new FakeStrategy(l -> incomplete);
        FakeStrategy headless = new FakeStrategy(l -> {
            throw PageFetchException.clientError(new RuntimeException("렌더 차단"));
        });
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        assertThrows(PageFetchException.class, () -> fallback(true, plain, headless, registry).extract(link, false, null));
        assertEquals(1, headless.calls);
        assertEquals(
            1.0,
            registry.counter("product.extract.escalation", "outcome", "failed", "category", "INCOMPLETE_SNAPSHOT").count()
        );
    }

    @Test
    @DisplayName("headless 가 꺼져 있으면 불완전 plain 결과도 그대로 반환한다 (응답 경계가 확정 실패로 닫는 기존 계약)")
    void headlessOffReturnsIncompleteAsIs() {
        FakeStrategy plain = new FakeStrategy(l -> incomplete);
        FakeStrategy headless = new FakeStrategy(l -> {
            throw new IllegalStateException("headless 는 호출되면 안 됨");
        });

        ProductSnapshot result = fallback(false, plain, headless).extract(link, false, null);

        assertEquals(incomplete, result);
        assertEquals(0, headless.calls);
    }

    @Test
    @DisplayName("LLM 의 상품 아님 확정(ProductSnapshotException)은 에스컬레이트하지 않고 그대로 전파한다")
    void productSnapshotFailureIsNotEscalated() {
        // 상품이 아니라는 판정은 fetch 축 실패가 아니다 — CSR 셸의 no-data 는 plain 전략(DefaultProductLinkExtractor)이
        // 이미 escalatable 로 재분류해 던지므로, 여기까지 온 ProductSnapshotException 은 브라우저로 다시 봐도 상품이
        // 되지 않는다.
        FakeStrategy plain = new FakeStrategy(l -> {
            throw ProductSnapshotException.notProductPage();
        });
        FakeStrategy headless = new FakeStrategy(l -> snapshot);

        assertThrows(ProductSnapshotException.class, () -> fallback(true, plain, headless).extract(link, false, null));
        assertEquals(0, headless.calls);
    }




    @Test
    @DisplayName("허락 없는 요청도 escalatable 이 아닌 실패는 그대로 전파한다 (SSRF 차단은 승격 대상이 아니다)")
    void unauthorizedPropagatesNonEscalatableFailure() {
        FakeStrategy plain = new FakeStrategy(l -> {
            throw PageFetchException.blockedHost();
        });
        FakeStrategy headless = new FakeStrategy(l -> {
            throw new IllegalStateException("headless 는 호출되면 안 됨");
        });

        assertThrows(PageFetchException.class, () -> fallback(true, plain, headless).extract(link, false, null));
        assertEquals(0, headless.calls);
    }

    @Test
    @DisplayName("허락이 없어도 불완전 결과는 headless 로 승격한다 (허락은 승격 조건이 아니다)")
    void unauthorizedStillEscalates() {
        // 브라우저로 여는 것 자체는 신원을 밝히고 하는 일이라 허락을 전제하지 않는다. 허락이 여는 것은
        // 렌더 서비스의 우회 수단뿐이고, 그 판단은 이 클래스가 아니라 렌더 경계 너머에 있다.
        FakeStrategy plain = new FakeStrategy(l -> incomplete);
        ProductSnapshot rescued =
            new ProductSnapshot(null, "톡딜 상품", "https://cdn.example.com/p.png", 23_900, null);
        FakeStrategy headless = new FakeStrategy(l -> rescued);

        ProductSnapshot result = fallback(true, plain, headless).extract(link, false, null);

        assertEquals(rescued, result);
        assertEquals(1, headless.calls);
        assertEquals(Boolean.FALSE, headless.lastAuthorized);
    }

    @Test
    @DisplayName("허락 플래그는 headless 전략까지 그대로 전달된다")
    void authorizationReachesHeadlessStrategy() {
        // 이 전달이 끊기면 허락받은 대상이 조용히 정직 모드로 가거나 그 반대가 된다 — 반환값으로는 안 드러난다.
        FakeStrategy plain = new FakeStrategy(l -> {
            throw PageFetchException.clientError(new RuntimeException("403"));
        });
        FakeStrategy headless = new FakeStrategy(l -> snapshot);

        fallback(true, plain, headless).extract(link, true, null);

        assertEquals(Boolean.TRUE, headless.lastAuthorized);
    }
}
