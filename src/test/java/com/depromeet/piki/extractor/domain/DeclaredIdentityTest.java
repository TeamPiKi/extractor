package com.depromeet.piki.extractor.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class DeclaredIdentityTest {

    @ParameterizedTest(name = "[{0}] 은 그 값 그대로 실린다")
    @ValueSource(strings = {"PiKi", "PiKi/2.0", "a.b_c-d/1.0", "1234567890123456789012345678901234567890"})
    void acceptsContractFormat(String requested) {
        DeclaredIdentity identity = DeclaredIdentity.fromRequest(requested);

        assertFalse(identity.isDefault());
        assertEquals(requested, identity.value());
        assertEquals(requested, identity.toString());
    }

    @ParameterizedTest(name = "생략·빈 값 [{0}] 은 기본 신원이다")
    @NullAndEmptySource
    void treatsBlankAsDefault(String requested) {
        assertTrue(DeclaredIdentity.fromRequest(requested).isDefault());
    }

    @Test
    @DisplayName("기본 신원은 렌더 요청에 빈 값으로 나가고 로그에는 실제 이름으로 찍힌다")
    void defaultKeepsEmptyWireValueButLogsActualName() {
        assertEquals("", DeclaredIdentity.DEFAULT.value());
        assertEquals("PiKi", DeclaredIdentity.DEFAULT.toString());
    }

    /** 헤더에 그대로 실리는 값이다. 줄바꿈·따옴표가 그대로 나가면 헤더가 깨지거나 다른 헤더가 끼어든다. */
    @ParameterizedTest(name = "형식 위반 [{0}] 은 기본 신원이다")
    @ValueSource(strings = {
        "PiKi (+https://example.com)",
        "a/b/c",
        "/1.0",
        "PiKi/",
        " ",
        "PiKi\r\nX-Evil: 1",
        "PiKi\n",
        "\"PiKi\";v=\"2\"",
        "피키",
        "12345678901234567890123456789012345678901",
    })
    void rejectsMalformedValue(String requested) {
        assertTrue(DeclaredIdentity.fromRequest(requested).isDefault());
    }
}
