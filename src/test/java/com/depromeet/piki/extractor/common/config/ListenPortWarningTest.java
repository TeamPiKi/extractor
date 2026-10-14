package com.depromeet.piki.extractor.common.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.env.MockEnvironment;

@ExtendWith(OutputCaptureExtension.class)
class ListenPortWarningTest {

    @Test
    @DisplayName("PORT env 없이 뜨면 기본 포트로 리슨한다는 경고를 남긴다")
    void warnsWithoutPortEnv(CapturedOutput output) {
        var environment = new MockEnvironment().withProperty("server.port", "1234");

        new ListenPortWarning(environment).warnIfDefaultPort();

        assertTrue(output.getOut().contains("PORT env 없음 - 기본 포트 1234 로 리슨"));
    }

    @Test
    @DisplayName("PORT env 를 받으면 경고하지 않는다")
    void staysQuietWithPortEnv(CapturedOutput output) {
        var environment = new MockEnvironment().withProperty("PORT", "1234");

        new ListenPortWarning(environment).warnIfDefaultPort();

        assertFalse(output.getOut().contains("PORT env 없음"));
    }
}
