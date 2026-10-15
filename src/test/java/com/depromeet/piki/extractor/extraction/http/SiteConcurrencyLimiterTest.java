package com.depromeet.piki.extractor.extraction.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.depromeet.piki.extractor.common.exception.ExtractionErrorCode;
import com.depromeet.piki.extractor.domain.ProductLink;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SiteConcurrencyLimiterTest {

    @ParameterizedTest
    @CsvSource({
        "www.musinsa.com, musinsa.com",
        "m.musinsa.com, musinsa.com",
        "smartstore.naver.com, naver.com",
        "naver.me, naver.me",
        "shop.example.co.kr, example.co.kr",
        "localhost, localhost",
        "10.0.1.5, 10.0.1.5",
    })
    @DisplayName("사이트는 공개 접미사 목록 기준 등록 도메인이고 그 밖의 host 는 그대로다")
    void siteOfGroupsByRegistrableDomain(String host, String site) {
        assertEquals(site, SiteConcurrencyLimiter.siteOf(host));
    }

    @Test
    @DisplayName("같은 사이트의 자리가 차 있으면 기다리다 헤드리스로 넘어가지 않는 일시 실패로 끝낸다")
    void sameSiteOverLimitFailsTransiently() throws Exception {
        SiteConcurrencyLimiter limiter = new SiteConcurrencyLimiter(1, Duration.ofMillis(100));
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<String> first = executor.submit(() -> limiter.call(link("https://www.musinsa.com/p/1"), () -> {
                holding.countDown();
                await(release);
                return "first";
            }));
            holding.await(5, TimeUnit.SECONDS);

            SiteBusyException e = assertThrows(
                SiteBusyException.class,
                () -> limiter.call(link("https://m.musinsa.com/p/2"), () -> "second")
            );
            release.countDown();

            assertEquals("first", first.get(5, TimeUnit.SECONDS));
            assertEquals(ExtractionErrorCode.UPSTREAM_ERROR, e.code());
            assertFalse(e.permanent());
        }
    }

    @Test
    @DisplayName("다른 사이트는 같은 사이트의 자리와 무관하게 바로 진행한다")
    void otherSiteIsNotBlocked() throws Exception {
        SiteConcurrencyLimiter limiter = new SiteConcurrencyLimiter(1, Duration.ofMillis(100));
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            executor.submit(() -> limiter.call(link("https://www.musinsa.com/p/1"), () -> {
                holding.countDown();
                await(release);
                return "first";
            }));
            holding.await(5, TimeUnit.SECONDS);

            assertEquals("other", limiter.call(link("https://www.29cm.co.kr/p/1"), () -> "other"));
            release.countDown();
        }
    }

    @Test
    @DisplayName("fetch 가 실패해도 자리를 돌려준다")
    void releasesOnFailure() {
        SiteConcurrencyLimiter limiter = new SiteConcurrencyLimiter(1, Duration.ofMillis(100));
        ProductLink link = link("https://www.musinsa.com/p/1");

        assertThrows(IllegalStateException.class, () -> limiter.call(link, () -> {
            throw new IllegalStateException("fetch 실패");
        }));

        assertEquals("next", limiter.call(link, () -> "next"));
    }

    private static ProductLink link(String url) {
        return ProductLink.parse(url);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
