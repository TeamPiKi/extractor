package com.depromeet.piki.extractor.extraction.rule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 배포본을 못 읽었을 때의 규칙(TeamPiKi/infra contracts/extraction-api.md 7절 "보관"). */
class RefreshingMallRuleSourceTest {

    private static final String TWO_RULES = """
        {"version": 12, "rules": [
          {"id": 1, "host": "oliveyoung.co.kr", "futureField": "모르는 필드"},
          {"id": 2, "host": "29cm.co.kr", "absentOriginalPrice": "NO_DISCOUNT"}
        ]}""";

    private Supplier<Optional<String>> stored = () -> {
        throw new IllegalStateException("저장소 응답을 세팅하지 않았다");
    };

    private RefreshingMallRuleSource source() {
        return new RefreshingMallRuleSource(
            () -> stored.get(),
            properties("redis.test:6379", "secret")
        );
    }

    private static MallRuleProperties properties(String address, String password) {
        return new MallRuleProperties(
            address, "extraction-rules-reader", password, Duration.ofSeconds(60), Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("배포본을 읽으면 그 판의 규칙을 적용하고 모르는 필드는 무시한다")
    void loadsSnapshot() {
        RefreshingMallRuleSource source = source();
        stored = () -> Optional.of(TWO_RULES);

        source.refresh();

        assertEquals(12, source.current().version());
        assertEquals(2, source.current().size());
    }

    @Test
    @DisplayName("읽기 전에는 규칙 0개다")
    void startsEmpty() {
        assertSame(MallRules.NONE, source().current());
    }

    @Test
    @DisplayName("키가 없어지면 직전 배포본을 버리고 곧바로 규칙 0개가 된다")
    void missingKeyClearsRules() {
        RefreshingMallRuleSource source = source();
        stored = () -> Optional.of(TWO_RULES);
        source.refresh();

        stored = Optional::empty;
        source.refresh();

        assertEquals(0, source.current().size());
    }

    @Test
    @DisplayName("규칙 목록이 빈 배포본도 규칙 0개로 곧바로 반영한다")
    void emptyRuleListClearsRules() {
        RefreshingMallRuleSource source = source();
        stored = () -> Optional.of(TWO_RULES);
        source.refresh();

        stored = () -> Optional.of("{\"version\": 13, \"rules\": []}");
        source.refresh();

        assertEquals(13, source.current().version());
        assertEquals(0, source.current().size());
    }

    @Test
    @DisplayName("저장소에 닿지 못하면 직전 배포본을 계속 쓰고 예외를 밖으로 내지 않는다")
    void unreachableStoreKeepsPreviousRules() {
        RefreshingMallRuleSource source = source();
        stored = () -> Optional.of(TWO_RULES);
        source.refresh();

        stored = () -> {
            throw new IllegalStateException("connection refused");
        };
        source.refresh();

        assertEquals(12, source.current().version());
        assertEquals(2, source.current().size());
    }

    @Test
    @DisplayName("값을 JSON 으로 해석하지 못하면 직전 배포본을 계속 쓴다")
    void brokenSnapshotKeepsPreviousRules() {
        RefreshingMallRuleSource source = source();
        stored = () -> Optional.of(TWO_RULES);
        source.refresh();

        stored = () -> Optional.of("{\"version\": 13, \"rules\": [");
        source.refresh();

        assertEquals(12, source.current().version());
    }

    @Test
    @DisplayName("처음부터 저장소에 닿지 못하면 규칙 0개로 동작한다")
    void unreachableStoreAtStartMeansNoRules() {
        RefreshingMallRuleSource source = source();
        stored = () -> {
            throw new IllegalStateException("connection refused");
        };

        source.refresh();

        assertSame(MallRules.NONE, source.current());
    }

    @Test
    @DisplayName("해석하지 못하는 규칙 하나는 그 규칙만 버리고 나머지를 적용한다")
    void dropsOnlyInvalidRule() {
        RefreshingMallRuleSource source = source();
        stored = () -> Optional.of("""
            {"version": 14, "rules": [
              {"id": 1, "host": "oliveyoung.co.kr", "name": {}},
              {"id": 2, "host": "29cm.co.kr"}
            ]}""");

        source.refresh();

        assertEquals(1, source.current().size());
    }

    @Test
    @DisplayName("host:port 형태가 아닌 주소는 부팅에서 실패한다")
    void rejectsBrokenRedisAddress() {
        assertThrows(IllegalStateException.class, () -> properties("redis://redis.test:6379", "secret"));
        assertThrows(IllegalStateException.class, () -> properties("redis.test", "secret"));
    }

    @Test
    @DisplayName("주소나 비밀번호가 없으면 읽지 않는다")
    void disabledWithoutAddressOrPassword() {
        assertFalse(properties("", "secret").enabled());
        assertFalse(properties("10.0.1.45:6379", "").enabled());
        assertTrue(properties("10.0.1.45:6379", "secret").enabled());
    }

    @Test
    @DisplayName("설정의 문자열 표현에 비밀번호가 실리지 않는다")
    void doesNotLeakPassword() {
        assertFalse(properties("10.0.1.45:6379", "s3cret").toString().contains("s3cret"));
    }

    @Test
    @DisplayName("배포본의 키는 계약에 적힌 이름이다")
    void readsContractKey() {
        assertEquals("piki:extraction:rules", RedisRuleSnapshotReader.KEY);
    }
}
