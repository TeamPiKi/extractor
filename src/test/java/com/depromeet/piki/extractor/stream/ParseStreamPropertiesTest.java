package com.depromeet.piki.extractor.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.depromeet.piki.extractor.stream.ParseStreamProperties.Source;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ParseStreamPropertiesTest {

    @Test
    @DisplayName("주소와 비밀번호가 둘 다 있어야 그 환경의 스트림을 읽는다")
    void enabledNeedsAddressAndPassword() {
        assertTrue(new Source("prod", "10.0.1.5:6379", "extraction-stream-worker", "secret").enabled());
        assertFalse(new Source("prod", "10.0.1.5:6379", "extraction-stream-worker", "").enabled());
        assertFalse(new Source("prod", "", "extraction-stream-worker", "secret").enabled());
    }

    @Test
    @DisplayName("주소에서 host 와 port 를 나눠 읽는다")
    void splitsHostAndPort() {
        Source source = new Source("dev", "redis.internal:6380", "extraction-stream-worker", "secret");

        assertEquals("redis.internal", source.redisHost());
        assertEquals(6380, source.redisPort());
    }

    @Test
    @DisplayName("host:port 형태가 아닌 주소는 부팅에서 막는다")
    void rejectsMalformedAddress() {
        assertThrows(IllegalStateException.class, () -> new Source("dev", "redis://10.0.1.5:6379", "worker", "secret"));
    }

    @Test
    @DisplayName("문자열 표현에 비밀번호를 싣지 않는다")
    void hidesPassword() {
        assertFalse(new Source("prod", "10.0.1.5:6379", "extraction-stream-worker", "secret").toString().contains("secret"));
    }
}
