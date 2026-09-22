package com.depromeet.piki.extractor.extraction.http;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.apache.hc.client5.http.cookie.BasicCookieStore;
import org.apache.hc.client5.http.cookie.Cookie;
import org.apache.hc.client5.http.cookie.CookieStore;
import org.springframework.stereotype.Component;

/**
 * fetch 한 번 동안만 사는 쿠키 저장소.
 *
 * <p>왜 드는가: 딥링크가 "쿠키를 심고 자기 자신으로 302" 하는 바운스를 쓴다(airbridge 실측). 쿠키를 안 들고 있으면
 * 같은 자리를 계속 돌다 redirect 상한을 소진해 확정 실패가 되고, 브라우저는 받은 쿠키를 다음 hop 에 실어 빠져나온다.
 * 지금 fetch 경로는 쿠키를 하나도 보관하지 않아 그런 링크를 구조적으로 못 푼다.
 *
 * <p>왜 요청 스코프인가: HttpClient 하나를 모든 fetch 가 공유하므로 저장소도 공유하면 남의 링크에서 받은 쿠키가 다음
 * 사용자의 요청에 실린다. {@link RequestScopedDnsResolver} 와 같은 이유·같은 모양으로 ThreadLocal 에 두고, fetch 가
 * 끝날 때 {@link #clear()} 로 비운다. 동기 호출이라 한 fetch 의 모든 hop 이 같은 스레드에서 돈다.
 */
@Component
public class RequestScopedCookieStore implements CookieStore {

    private final ThreadLocal<BasicCookieStore> delegate = ThreadLocal.withInitial(BasicCookieStore::new);

    @Override
    public void addCookie(Cookie cookie) {
        delegate.get().addCookie(cookie);
    }

    @Override
    public List<Cookie> getCookies() {
        return delegate.get().getCookies();
    }

    /** Date 쪽은 deprecated 지만 인터페이스가 아직 abstract 로 들고 있어 둘 다 위임한다. */
    @Override
    public boolean clearExpired(Date date) {
        return delegate.get().clearExpired(date);
    }

    @Override
    public boolean clearExpired(Instant instant) {
        return delegate.get().clearExpired(instant);
    }

    @Override
    public void clear() {
        delegate.get().clear();
    }
}
