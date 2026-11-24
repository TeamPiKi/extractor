package com.depromeet.piki.extractor.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.depromeet.piki.extractor.stream.ParseStream.Abandoned;
import com.depromeet.piki.extractor.stream.ParseStream.Delivery;
import io.lettuce.core.Consumer;
import io.lettuce.core.Range;
import io.lettuce.core.RedisClient;
import io.lettuce.core.XReadArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * 실제 Redis 에 명령이 계약대로 나가는지 확인한다. 확인 응답에 쓰는 XACKDEL 은 Redis 8.2 이상에만 있다.
 * 스트림과 정책 키를 지우므로 비어 있는 Redis 에만 붙인다
 * ({@code docker run --rm -p 6390:6379 redis:8-alpine} 뒤 {@code PARSE_STREAM_E2E_REDIS_URL=redis://localhost:6390}).
 */
@EnabledIfEnvironmentVariable(named = "PARSE_STREAM_E2E_REDIS_URL", matches = ".+")
class LettuceParseStreamE2ETest {

    private static final String URL = System.getenv("PARSE_STREAM_E2E_REDIS_URL");
    private static final Map<String, String> REQUEST = Map.of("requestId", "42", "inputType", "LINK", "inputValue", "https://shop.example.com/p/1");

    @Test
    @DisplayName("꺼낸 요청에 결과를 쓰고 확인 응답을 보내면 요청은 대기 목록과 스트림에서 사라진다")
    void roundTrip() {
        try (Redis redis = new Redis()) {
            ParseStream stream = new LettuceParseStream(URL, "e2e-a", Duration.ofSeconds(5));
            stream.ensureGroup();
            stream.ensureGroup();
            redis.commands.xadd(ParseStreamContract.REQUEST_STREAM, REQUEST);

            List<Delivery> read = stream.readNew(Duration.ofMillis(200));
            stream.publishResult(Map.of("requestId", "42", "code", "OK"));
            stream.acknowledge(read.getFirst().id());

            assertEquals(REQUEST, read.getFirst().fields());
            assertEquals(0, redis.commands.xlen(ParseStreamContract.REQUEST_STREAM));
            assertEquals(0, redis.commands.xpending(ParseStreamContract.REQUEST_STREAM, ParseStreamContract.REQUEST_GROUP).getCount());
            assertEquals(
                Map.of("requestId", "42", "code", "OK"),
                redis.commands.xrange(ParseStreamContract.RESULT_STREAM, Range.unbounded()).getFirst().getBody()
            );
            assertTrue(stream.readNew(Duration.ofMillis(200)).isEmpty());
            stream.close();
        }
    }

    @Test
    @DisplayName("다른 소비자가 꺼내고 남긴 요청을 배달 횟수와 함께 찾아 이어받는다")
    void reclaim() {
        try (Redis redis = new Redis()) {
            ParseStream stream = new LettuceParseStream(URL, "e2e-b", Duration.ofSeconds(5));
            stream.ensureGroup();
            redis.commands.xadd(ParseStreamContract.REQUEST_STREAM, REQUEST);
            redis.commands.xreadgroup(
                Consumer.from(ParseStreamContract.REQUEST_GROUP, "gone"),
                XReadArgs.StreamOffset.lastConsumed(ParseStreamContract.REQUEST_STREAM)
            );

            List<Abandoned> abandoned = stream.findAbandoned(Duration.ZERO, 10);
            Optional<Delivery> claimed = stream.claim(abandoned.getFirst().id(), Duration.ZERO);

            assertEquals(1, abandoned.getFirst().deliveries());
            assertEquals(REQUEST, claimed.orElseThrow().fields());
            assertEquals(2, stream.findAbandoned(Duration.ZERO, 10).getFirst().deliveries());
            assertTrue(stream.findAbandoned(Duration.ofMinutes(10), 10).isEmpty());
            stream.close();
        }
    }

    @Test
    @DisplayName("정책 키가 없으면 empty, 있으면 그 문자열을 읽는다")
    void policy() {
        try (Redis redis = new Redis()) {
            ParseStream stream = new LettuceParseStream(URL, "e2e-c", Duration.ofSeconds(5));

            assertTrue(stream.readPolicy().isEmpty());
            redis.commands.set(ParseStreamContract.POLICY_KEY, "{\"version\": \"1\"}");
            assertEquals("{\"version\": \"1\"}", stream.readPolicy().orElseThrow());
            stream.close();
        }
    }

    @Test
    @DisplayName("대기 목록이 빈 다른 소비자 이름만 지우고, 요청을 쥔 이름과 자기 이름은 남긴다")
    void removesIdleConsumers() {
        try (Redis redis = new Redis()) {
            ParseStream stream = new LettuceParseStream(URL, "e2e-d", Duration.ofSeconds(5));
            stream.ensureGroup();
            stream.readNew(Duration.ofMillis(50));
            redis.commands.xgroupCreateconsumer(
                ParseStreamContract.REQUEST_STREAM, Consumer.from(ParseStreamContract.REQUEST_GROUP, "idle"));
            redis.commands.xadd(ParseStreamContract.REQUEST_STREAM, REQUEST);
            redis.commands.xreadgroup(
                Consumer.from(ParseStreamContract.REQUEST_GROUP, "holding"),
                XReadArgs.StreamOffset.lastConsumed(ParseStreamContract.REQUEST_STREAM)
            );

            stream.removeIdleConsumers(Duration.ofMillis(-1));

            assertEquals(1, stream.findAbandoned(Duration.ZERO, 10).size());
            assertEquals(2, redis.commands.xinfoConsumers(ParseStreamContract.REQUEST_STREAM, ParseStreamContract.REQUEST_GROUP).size());
            stream.close();
        }
    }

    /** 테스트마다 빈 상태에서 시작하고 끝나면 지운다. */
    private static final class Redis implements AutoCloseable {

        private final RedisClient client = RedisClient.create(URL);
        private final StatefulRedisConnection<String, String> connection = client.connect();
        private final RedisCommands<String, String> commands = connection.sync();

        private Redis() {
            clear();
        }

        private void clear() {
            commands.del(ParseStreamContract.REQUEST_STREAM, ParseStreamContract.RESULT_STREAM, ParseStreamContract.POLICY_KEY);
        }

        @Override
        public void close() {
            clear();
            connection.close();
            client.shutdown();
        }
    }
}
