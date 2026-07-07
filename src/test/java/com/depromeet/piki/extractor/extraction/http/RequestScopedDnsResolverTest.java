package com.depromeet.piki.extractor.extraction.http;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// 요청 스코프 DNS 캐시가 "한 fetch 동안 host 당 실제 조회 1회"를 보장하는지 검증한다.
// 이게 IP pin 의 핵심 계약 — 가드와 연결이 같은 IP 를 보게 해 DNS rebinding(두 조회가 다른 IP)을 닫는다.
// (Java 람다는 mutable 지역변수를 캡처하지 못하므로 AtomicInteger 로 호출 횟수를 센다.)
class RequestScopedDnsResolverTest {

    @Test
    @DisplayName("같은 host 를 여러 번 resolve 해도 실제 조회는 한 번뿐이다")
    void resolvesSameHostOnlyOnce() throws UnknownHostException {
        AtomicInteger calls = new AtomicInteger(0);
        RequestScopedDnsResolver resolver =
            new RequestScopedDnsResolver(host -> {
                calls.incrementAndGet();
                return new InetAddress[] {InetAddress.getByName("93.184.216.34")};
            });

        resolver.resolve("zigzag.kr");
        resolver.resolve("zigzag.kr");
        resolver.resolve("zigzag.kr");

        assertEquals(1, calls.get(), "한 요청 안에서는 host 당 한 번만 실제 DNS 를 타야 가드·연결이 같은 IP 를 본다");
    }

    @Test
    @DisplayName("clear 후에는 다시 실제 조회한다")
    void resolvesAgainAfterClear() throws UnknownHostException {
        AtomicInteger calls = new AtomicInteger(0);
        RequestScopedDnsResolver resolver =
            new RequestScopedDnsResolver(host -> {
                calls.incrementAndGet();
                return new InetAddress[] {InetAddress.getByName("93.184.216.34")};
            });

        resolver.resolve("zigzag.kr");
        resolver.clear();
        resolver.resolve("zigzag.kr");

        assertEquals(2, calls.get(), "clear 로 요청이 격리되어야 다음 fetch 가 새로 조회한다");
    }

    @Test
    @DisplayName("다른 host 는 각각 실제 조회한다")
    void resolvesEachHostSeparately() throws UnknownHostException {
        AtomicInteger calls = new AtomicInteger(0);
        RequestScopedDnsResolver resolver =
            new RequestScopedDnsResolver(host -> {
                calls.incrementAndGet();
                return new InetAddress[] {InetAddress.getByName("93.184.216.34")};
            });

        resolver.resolve("zigzag.kr");
        resolver.resolve("29cm.co.kr");

        assertEquals(2, calls.get());
    }

    @Test
    @DisplayName("스레드(요청) 간 캐시는 공유되지 않는다")
    void cacheIsNotSharedAcrossThreads() throws InterruptedException {
        AtomicInteger calls = new AtomicInteger(0);
        RequestScopedDnsResolver resolver =
            new RequestScopedDnsResolver(host -> {
                calls.incrementAndGet();
                return new InetAddress[] {InetAddress.getByName("93.184.216.34")};
            });
        int threads = 2;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService executor = Executors.newFixedThreadPool(threads);

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    resolver.resolve("zigzag.kr");
                    resolver.resolve("zigzag.kr"); // 같은 스레드 캐시라 1회
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                done.countDown();
            });
        }
        ready.await();
        start.countDown(); // 동시 출발 강제
        done.await();
        executor.shutdown();

        // ThreadLocal 이라 스레드(요청)별 캐시가 분리 → 각 스레드가 1회씩 실제 조회 = threads 회.
        // 캐시가 스레드 간 공유되면 1회로 떨어져 IP pin 의 요청 격리 계약이 깨진다.
        assertEquals(threads, calls.get());
    }
}
