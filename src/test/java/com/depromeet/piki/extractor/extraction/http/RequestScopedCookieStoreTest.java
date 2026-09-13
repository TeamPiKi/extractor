package com.depromeet.piki.extractor.extraction.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.hc.client5.http.cookie.Cookie;
import org.apache.hc.client5.http.impl.cookie.BasicClientCookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 쿠키가 fetch 경계를 넘지 않는다는 것이 이 저장소의 존재 이유다. 그 경계를 값으로 검증한다. */
class RequestScopedCookieStoreTest {

    private static BasicClientCookie cookie(String name, String domain) {
        BasicClientCookie cookie = new BasicClientCookie(name, "v");
        cookie.setDomain(domain);
        cookie.setPath("/");
        return cookie;
    }

    @Test
    @DisplayName("clear 한 뒤에는 앞선 fetch 의 쿠키가 남지 않는다")
    void clearDropsCookiesOfThePreviousFetch() {
        RequestScopedCookieStore store = new RequestScopedCookieStore();
        store.addCookie(cookie("bounce", "al.wconcept.co.kr"));

        store.clear();

        assertTrue(store.getCookies().isEmpty());
    }

    @Test
    @DisplayName("다른 스레드의 fetch 가 심은 쿠키는 보이지 않는다 - 동시 요청끼리 쿠키가 새면 안 된다")
    void cookiesDoNotLeakAcrossThreads() throws Exception {
        RequestScopedCookieStore store = new RequestScopedCookieStore();
        store.addCookie(cookie("mine", "a.example"));
        AtomicReference<List<Cookie>> seenByOther = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        Thread other = new Thread(() -> {
            seenByOther.set(store.getCookies());
            done.countDown();
        });
        other.start();
        assertTrue(done.await(5, TimeUnit.SECONDS), "다른 스레드가 끝나야 한다");

        assertTrue(seenByOther.get().isEmpty(), "다른 스레드는 빈 손이어야 한다");
        assertEquals(1, store.getCookies().size(), "자기 쿠키는 그대로여야 한다");
    }
}
