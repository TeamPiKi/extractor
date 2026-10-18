package com.depromeet.piki.extractor.extraction.http;

import com.depromeet.piki.extractor.domain.ProductLink;
import com.google.common.net.InternetDomainName;
import com.google.common.util.concurrent.Striped;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 몰 요청은 전부 고정 IP 하나에서 나가 몰의 IP 단위 제한에 걸리면 그 몰 전체가 막힌다. 같은 사이트로 동시에 나가는
 * 정적 fetch 수를 묶는다(#119). 사이트는 공개 접미사 목록 기준 등록 도메인이라 www·m 이 한 사이트다.
 */
@Component
public class SiteConcurrencyLimiter {

    /** 사이트 키가 늘어도 메모리가 자라지 않게 줄무늬로 나눈다. 다른 사이트가 같은 줄무늬를 나눌 확률은 무시할 만하다. */
    private static final int STRIPES = 1024;

    private final Striped<Semaphore> semaphores;
    private final Duration maxWait;

    @Autowired
    public SiteConcurrencyLimiter(SiteLimitProperties properties) {
        this(properties.maxConcurrent(), properties.maxWait());
    }

    public SiteConcurrencyLimiter(int maxConcurrent, Duration maxWait) {
        this.semaphores = Striped.lazyWeakSemaphore(STRIPES, maxConcurrent);
        this.maxWait = maxWait;
    }

    public <T> T call(ProductLink link, Supplier<T> fetch) {
        Semaphore semaphore = semaphores.get(siteOf(link.value().getHost()));
        acquire(semaphore);
        try {
            return fetch.get();
        } finally {
            semaphore.release();
        }
    }

    private void acquire(Semaphore semaphore) {
        try {
            if (!semaphore.tryAcquire(maxWait.toMillis(), TimeUnit.MILLISECONDS)) {
                throw SiteBusyException.waited(maxWait);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw SiteBusyException.waited(maxWait);
        }
    }

    static String siteOf(String host) {
        try {
            InternetDomainName name = InternetDomainName.from(host);
            return name.isUnderPublicSuffix() ? name.topPrivateDomain().toString() : host;
        } catch (IllegalArgumentException e) {
            return host;
        }
    }
}
