package com.depromeet.piki.extractor.extraction.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 신원은 설정 기본값이라 조용히 되돌아가도 컴파일과 다른 테스트가 전부 통과한다. 값 자체를 못 박는다. */
class FetchPropertiesTest {

    @Test
    @DisplayName("기본 UA 는 브라우저 호환 형식으로 시작하고 렌더 서비스와 같은 신원 토큰으로 끝난다")
    void defaultUserAgentDeclaresIdentity() {
        String userAgent = FetchProperties.defaults().userAgent();

        assertEquals("Piki/1.0 (+https://piki.day)", FetchProperties.IDENTITY_TOKEN);
        assertTrue(userAgent.startsWith("Mozilla/5.0 "), userAgent);
        assertTrue(userAgent.endsWith(" " + FetchProperties.IDENTITY_TOKEN), userAgent);
    }
}
