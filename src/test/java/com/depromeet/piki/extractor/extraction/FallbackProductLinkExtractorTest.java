package com.depromeet.piki.extractor.extraction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.depromeet.piki.extractor.domain.ProductLink;
import com.depromeet.piki.extractor.domain.ProductSnapshot;
import com.depromeet.piki.extractor.extraction.http.PageFetchException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// Fallback(진입점)이 "plain 먼저, 막히면 headless" 를 flag·escalatable 규칙대로 엮는지 Spring 없이 검증한다.
// 통합 테스트는 외부 경계(PageFetcher·GeminiClient)만 stub 하고 이 라우팅을 실제로 타므로, 분기 망라는 여기 단위에서.
// 두 전략은 실제 빈이 네트워크/브라우저를 요구해 단위로 세울 수 없어, LinkExtractionStrategy fake 로 "전략의 결과"만 주입한다.
// (escalation 메트릭의 category 태그 값은 ExtractionErrorCode 명이다.)
class FallbackProductLinkExtractorTest {

    private final ProductLink link = ProductLink.parse("https://shop.example.com/p");
    private final ProductSnapshot snapshot = new ProductSnapshot(null, "나이키", null, 99_000, null);

    private static class FakeStrategy implements LinkExtractionStrategy {
        private final Function<ProductLink, ProductSnapshot> fn;
        int calls = 0;

        FakeStrategy(Function<ProductLink, ProductSnapshot> fn) {
            this.fn = fn;
        }

        @Override
        public ProductSnapshot extract(ProductLink link) {
            calls++;
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

        ProductSnapshot result = fallback(false, plain, headless).extract(link, false);

        assertEquals(snapshot, result);
        assertEquals(1, plain.calls);
        assertEquals(0, headless.calls);
    }

    @Test
    @DisplayName("headless 가 꺼져 있으면 escalatable 차단이어도 plain 예외를 그대로 전파한다")
    void headlessOffPropagatesEscalatableFailure() {
        // behavior-neutral: 플래그가 꺼진 동안엔 차단(escalatable)이라도 에스컬레이트하지 않고 현재 동작(예외 전파)을 유지한다.
        FakeStrategy plain = new FakeStrategy(l -> {
            throw PageFetchException.clientError(new RuntimeException("403"));
        });
        FakeStrategy headless = new FakeStrategy(l -> snapshot);

        assertThrows(PageFetchException.class, () -> fallback(false, plain, headless).extract(link, false));
        assertEquals(0, headless.calls);
    }

    @Test
    @DisplayName("headless 가 켜져 있고 plain 이 escalatable 차단으로 막히면 headless 로 에스컬레이트하고 success 로 집계한다")
    void escalatesToHeadlessOnEscalatableFailure() {
        FakeStrategy plain = new FakeStrategy(l -> {
            throw PageFetchException.clientError(new RuntimeException("403"));
        });
        ProductSnapshot headlessSnapshot = new ProductSnapshot(null, "헤드리스 결과", null, 50_000, null);
        FakeStrategy headless = new FakeStrategy(l -> headlessSnapshot);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        ProductSnapshot result = fallback(true, plain, headless, registry).extract(link, false);

        assertEquals(headlessSnapshot, result);
        assertEquals(1, plain.calls);
        assertEquals(1, headless.calls);
        // clientError(4xx) → category=FETCH_CLIENT_ERROR. outcome·category 로 escalate 를 쪼개 낭비를 조사 가능하게 한다.
        assertEquals(
            1.0,
            registry.counter("product.extract.escalation", "outcome", "success", "category", "FETCH_CLIENT_ERROR").count()
        );
    }

    @Test
    @DisplayName("headless 가 켜져 있어도 plain 이 성공하면 headless 는 호출하지 않는다")
    void headlessOnButPlainSucceeds() {
        // 정상 흐름(가장 흔함): headless on 이어도 plain 이 성공하면 그 결과를 그대로 쓰고 headless 를 안 부른다.
        FakeStrategy plain = new FakeStrategy(l -> snapshot);
        FakeStrategy headless = new FakeStrategy(l -> {
            throw new IllegalStateException("headless 는 호출되면 안 됨");
        });

        ProductSnapshot result = fallback(true, plain, headless).extract(link, false);

        assertEquals(snapshot, result);
        assertEquals(1, plain.calls);
        assertEquals(0, headless.calls);
    }

    @Test
    @DisplayName("headless 가 Error 로 실패해도 failed 로 집계하고 전파한다")
    void headlessErrorIsCountedAndPropagated() {
        // headless 구현이 Error 계열로 실패해도(미구현·OOM 등), catch 가 Exception 으로 좁으면 outcome=failed
        // 집계가 조용히 빠질 수 있다. Throwable 을 잡아 집계하고 그대로 전파함을 고정한다.
        FakeStrategy plain = new FakeStrategy(l -> {
            throw PageFetchException.clientError(new RuntimeException("403"));
        });
        FakeStrategy headless = new FakeStrategy(l -> {
            throw new Error("headless 미구현");
        });
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        assertThrows(Error.class, () -> fallback(true, plain, headless, registry).extract(link, false));
        assertEquals(
            1.0,
            registry.counter("product.extract.escalation", "outcome", "failed", "category", "FETCH_CLIENT_ERROR").count()
        );
    }

    @Test
    @DisplayName("headless 도 실패하면 그 예외를 전파하고 escalation 을 failed 로 집계한다")
    void headlessFailureIsCountedAndPropagated() {
        // 호출자(PIKI-Server 워커)의 재시도 판정과 맞물리는 지점이라, headless 예외가 그대로 상위로 전파돼야 한다.
        // "폴백했는데도 못 가져온" 이 outcome=failed 로 집계돼, 무조건-폴백의 낭비·한계를 관측할 수 있어야 한다.
        FakeStrategy plain = new FakeStrategy(l -> {
            throw PageFetchException.clientError(new RuntimeException("403"));
        });
        FakeStrategy headless = new FakeStrategy(l -> {
            throw new RuntimeException("headless 도 실패");
        });
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        assertThrows(RuntimeException.class, () -> fallback(true, plain, headless, registry).extract(link, false));
        assertEquals(1, plain.calls);
        assertEquals(1, headless.calls);
        assertEquals(
            1.0,
            registry.counter("product.extract.escalation", "outcome", "failed", "category", "FETCH_CLIENT_ERROR").count()
        );
    }

    @Test
    @DisplayName("headlessFirst 힌트가 오면 plain 을 건너뛰고 headless 로 직행하며 success 로 집계한다")
    void headlessFirstSkipsPlain() {
        FakeStrategy plain = new FakeStrategy(l -> {
            throw new IllegalStateException("plain 은 호출되면 안 됨");
        });
        ProductSnapshot headlessSnapshot = new ProductSnapshot(null, "헤드리스 결과", null, 50_000, null);
        FakeStrategy headless = new FakeStrategy(l -> headlessSnapshot);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        ProductSnapshot result = fallback(true, plain, headless, registry).extract(link, true);

        assertEquals(headlessSnapshot, result);
        assertEquals(0, plain.calls);
        assertEquals(1, headless.calls);
        assertEquals(1.0, registry.counter("product.extract.headless_first", "outcome", "success").count());
    }

    @Test
    @DisplayName("headlessFirst 직행이 실패하면 plain 으로 되돌리지 않고 failed 로 집계한 뒤 그대로 전파한다")
    void headlessFirstFailurePropagatesWithoutPlainFallback() {
        // 직행 정책은 "plain 이 항상 차단되는 host" 지정이라 실패해도 plain 재시도는 낭비다 — 재시도는 호출자
        // outbox recover 축이, 정책 오지정은 백오피스 롤백이 진다. outcome=failed 시계열이 그 오지정의 추세 신호다.
        FakeStrategy plain = new FakeStrategy(l -> snapshot);
        FakeStrategy headless = new FakeStrategy(l -> {
            throw new RuntimeException("headless 실패");
        });
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        assertThrows(RuntimeException.class, () -> fallback(true, plain, headless, registry).extract(link, true));
        assertEquals(0, plain.calls);
        assertEquals(1, headless.calls);
        assertEquals(1.0, registry.counter("product.extract.headless_first", "outcome", "failed").count());
    }

    @Test
    @DisplayName("headless 가 꺼져 있으면 headlessFirst 힌트도 무시하고 plain 에 위임한다")
    void headlessFirstIgnoredWhenDisabled() {
        // 호출자(PIKI-Server)의 라우팅 정책이 이 서비스의 스위치보다 앞설 수 없다 — 스위치가 꺼진 동안은 zero-diff.
        FakeStrategy plain = new FakeStrategy(l -> snapshot);
        FakeStrategy headless = new FakeStrategy(l -> {
            throw new IllegalStateException("headless 는 호출되면 안 됨");
        });

        ProductSnapshot result = fallback(false, plain, headless).extract(link, true);

        assertEquals(snapshot, result);
        assertEquals(1, plain.calls);
        assertEquals(0, headless.calls);
    }

    @Test
    @DisplayName("headless 가 켜져 있어도 escalatable 이 아닌 실패는 전파하고 headless 를 호출하지 않는다")
    void nonEscalatableFailureIsNeverEscalated() {
        // SSRF 로 우리가 막은 내부망(blockedHost)은 escalatable=false — 절대 헤드리스로 넘기지 않는다("무조건 폴백"의 유일 예외).
        FakeStrategy plain = new FakeStrategy(l -> {
            throw PageFetchException.blockedHost();
        });
        FakeStrategy headless = new FakeStrategy(l -> snapshot);

        assertThrows(PageFetchException.class, () -> fallback(true, plain, headless).extract(link, false));
        assertEquals(0, headless.calls);
    }
}
