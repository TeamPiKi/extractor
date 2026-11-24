package com.depromeet.piki.extractor.stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 계약의 시간 관계(TeamPiKi/infra contracts/extraction-api.md 8절 "이어받기")를 기동 때 막는지 본다. */
class ParseStreamContractTest {

    private static final Duration WITHIN = ParseStreamContract.PROCESSING_TIMEOUT.minusSeconds(30);

    @Test
    @DisplayName("기한과 가장 긴 단계의 합이 처리 상한 안이고 찾는 주기가 계약 안이면 통과한다")
    void acceptsHonoredTimings() {
        assertDoesNotThrow(() -> ParseStreamContract.requireHonored(
            WITHIN, Duration.ofSeconds(30), ParseStreamContract.REDELIVERY_SCAN_INTERVAL));
    }

    @Test
    @DisplayName("기한 직전에 시작한 가장 긴 단계가 처리 상한을 넘기면 기동을 막는다")
    void rejectsProcessingBeyondTimeout() {
        assertThrows(IllegalStateException.class, () -> ParseStreamContract.requireHonored(
            WITHIN, Duration.ofSeconds(31), ParseStreamContract.REDELIVERY_SCAN_INTERVAL));
    }

    @Test
    @DisplayName("이어받을 요청을 찾는 주기가 계약보다 길면 기동을 막는다")
    void rejectsSlowReclaimScan() {
        assertThrows(IllegalStateException.class, () -> ParseStreamContract.requireHonored(
            WITHIN, Duration.ofSeconds(30), ParseStreamContract.REDELIVERY_SCAN_INTERVAL.plusSeconds(1)));
    }
}
