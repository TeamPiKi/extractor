package com.depromeet.piki.extractor.extraction;

import com.depromeet.piki.contracts.extraction.v1.ExtractionProto;
import java.time.Duration;
import java.util.OptionalLong;
import java.util.function.Supplier;

/**
 * 호출자가 이미 끊었을 시점 뒤에는 렌더·LLM 단계를 새로 시작하지 않는다. 시작하면 호출자 재요청과 겹쳐 renderer 의
 * 단일 슬롯과 LLM 비용을 아무도 받지 않는 요청이 쓴다(#117). 진행 중인 단계는 끊지 않는다.
 */
public final class ExtractionDeadline {

    /** 요청이 컨트롤러에 닿기까지의 지연과 응답을 돌려보내는 시간 몫. */
    static final Duration MARGIN = Duration.ofSeconds(5);

    static final Duration LINK_BUDGET = Duration.ofSeconds(ExtractionProto.getDescriptor()
            .findServiceByName("ExtractionService")
            .getOptions()
            .getExtension(ExtractionProto.callerReadTimeoutSeconds))
        .minus(MARGIN);

    private static final ScopedValue<Long> DEADLINE_NANOS = ScopedValue.newInstance();

    private ExtractionDeadline() {
    }

    public static <T> T withinLinkBudget(Supplier<T> body) {
        return within(LINK_BUDGET, body);
    }

    static <T> T within(Duration budget, Supplier<T> body) {
        return ScopedValue.where(DEADLINE_NANOS, System.nanoTime() + budget.toNanos()).call(body::get);
    }

    /** 기한이 묶이지 않은 호출(이미지 경로·단위 테스트)은 막지 않는다. */
    static void requireNotPassed(String stage) {
        if (passed()) {
            throw DeadlineExceededException.before(stage);
        }
    }

    /** 필수가 아닌 단계(정가 보강)가 조용히 건너뛸지 판정하는 용도. 필수 단계는 requireNotPassed 로 끊는다. */
    static boolean passed() {
        return DEADLINE_NANOS.isBound() && System.nanoTime() - DEADLINE_NANOS.get() >= 0;
    }

    /** 기한이 묶이지 않았으면 empty. 부가 단계가 "남은 시간에 비해 긴 호출을 시작했나" 를 로그로 남기는 용도. */
    static OptionalLong remainingMillis() {
        if (!DEADLINE_NANOS.isBound()) {
            return OptionalLong.empty();
        }
        return OptionalLong.of((DEADLINE_NANOS.get() - System.nanoTime()) / 1_000_000);
    }
}
