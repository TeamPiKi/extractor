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
 * <p>왜 드는가: HttpClient5 는 저장소를 지정하지 않아도 자기 기본 저장소로 쿠키를 보관해 다음 요청에 싣는다(실측).
 * 그 저장소는 클라이언트 빈 하나에 묶여 프로세스 수명만큼 살므로, 어떤 링크에서 받은 쿠키가 뒤이은 다른 사용자의
 * fetch 에 실리고 만료 쿠키도 쌓인다. 쿠키 보관 자체는 그대로 두되 수명을 fetch 하나로 끊는 것이 이 클래스다.
 *
 * <p>쿠키 보관이 필요한 이유는 따로 있다 - 딥링크가 "쿠키를 심고 자기 자신으로 302" 하는 바운스를 쓴다(airbridge
 * 실측). 브라우저는 받은 쿠키를 다음 hop 에 실어 빠져나온다.
 *
 * <p>{@link RequestScopedDnsResolver} 와 같은 이유·같은 모양으로 ThreadLocal 에 두고 fetch 가 끝날 때
 * {@link #clear()} 로 비운다. 동기 호출이라 한 fetch 의 모든 hop 이 같은 스레드에서 돈다.
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
