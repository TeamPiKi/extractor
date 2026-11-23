package com.depromeet.piki.extractor.extraction.rule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
            new MallRuleProperties("redis://reader:secret@redis.test:6379", Duration.ofSeconds(60), Duration.ofSeconds(2))
        );
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
    @DisplayName("Redis 주소를 해석하지 못하는 설정은 부팅에서 실패한다")
    void rejectsBrokenRedisUrl() {
        assertThrows(
            IllegalStateException.class,
            () -> new MallRuleProperties("redis.test:6379", Duration.ofSeconds(60), Duration.ofSeconds(2))
        );
    }

    @Test
    @DisplayName("깨진 주소의 부팅 실패 메시지와 설정의 문자열 표현에 비밀번호가 실리지 않는다")
    void doesNotLeakPassword() {
        IllegalStateException unencodedSpace = assertThrows(
            IllegalStateException.class,
            () -> new MallRuleProperties("redis://reader:s3 cret@host:6379", Duration.ofSeconds(60), Duration.ofSeconds(2))
        );
        IllegalStateException unencodedHash = assertThrows(
            IllegalStateException.class,
            () -> new MallRuleProperties("redis://reader:s3#cret@host:6379", Duration.ofSeconds(60), Duration.ofSeconds(2))
        );
        MallRuleProperties valid =
            new MallRuleProperties("redis://reader:s3cret@host:6379", Duration.ofSeconds(60), Duration.ofSeconds(2));

        assertFalse(unencodedSpace.getMessage().contains("cret"));
        assertNull(unencodedSpace.getCause());
        assertFalse(unencodedHash.getMessage().contains("s3"));
        assertFalse(valid.toString().contains("s3cret"));
    }

    @Test
    @DisplayName("배포본의 키는 계약에 적힌 이름이다")
    void readsContractKey() {
        assertEquals("piki:extraction:rules", RedisRuleSnapshotReader.KEY);
    }
}
