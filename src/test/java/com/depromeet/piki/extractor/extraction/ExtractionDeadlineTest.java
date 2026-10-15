package com.depromeet.piki.extractor.extraction;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.depromeet.piki.contracts.extraction.v1.ExtractionProto;
import com.depromeet.piki.extractor.common.exception.ExtractionErrorCode;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ExtractionDeadlineTest {

    @Test
    @DisplayName("기한이 묶이지 않은 호출은 막지 않는다")
    void unboundCallPasses() {
        assertDoesNotThrow(() -> ExtractionDeadline.requireNotPassed("llm"));
    }

    @Test
    @DisplayName("기한 안에서는 단계를 시작한다")
    void withinBudgetPasses() {
        assertDoesNotThrow(() -> ExtractionDeadline.within(Duration.ofMinutes(1), () -> {
            ExtractionDeadline.requireNotPassed("llm");
            return null;
        }));
    }

    @Test
    @DisplayName("기한이 지나면 일시 실패 UPSTREAM_ERROR 로 단계를 시작하지 않는다")
    void passedDeadlineFailsTransiently() {
        DeadlineExceededException e = assertThrows(
            DeadlineExceededException.class,
            () -> ExtractionDeadline.within(Duration.ZERO, () -> {
                ExtractionDeadline.requireNotPassed("headless");
                return null;
            })
        );

        assertEquals(ExtractionErrorCode.UPSTREAM_ERROR, e.code());
        assertFalse(e.permanent());
    }

    @Test
    @DisplayName("링크 기한은 계약의 호출자 read 타임아웃보다 짧다")
    void linkBudgetEndsBeforeCallerGivesUp() {
        Duration callerReadTimeout = Duration.ofSeconds(ExtractionProto.getDescriptor()
            .findServiceByName("ExtractionService")
            .getOptions()
            .getExtension(ExtractionProto.callerReadTimeoutSeconds));

        assertTrue(ExtractionDeadline.LINK_BUDGET.isPositive());
        assertTrue(ExtractionDeadline.LINK_BUDGET.compareTo(callerReadTimeout) < 0);
    }
}
