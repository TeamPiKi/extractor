package com.depromeet.piki.extractor.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.lettuce.core.RedisClient;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.XReadArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * 읽기 스레드와 종료까지 실제 Redis 에 붙여 한 바퀴 돌린다. 붙이는 Redis 의 조건은 LettuceParseStreamE2ETest 와 같다.
 * 추출 없이 닫히는 요청(모르는 입력 종류)을 써서 추출 파이프라인은 타지 않는다.
 */
@EnabledIfEnvironmentVariable(named = "PARSE_STREAM_E2E_REDIS_URL", matches = ".+")
class ParseStreamConsumersE2ETest {

    private static final String URL = System.getenv("PARSE_STREAM_E2E_REDIS_URL");

    @Test
    @DisplayName("기동하면 그룹을 만들어 들어온 요청의 결과를 결과 스트림에 쓰고 요청을 지운다")
    void consumesRequest() {
        RedisClient client = RedisClient.create(URL);
        try (StatefulRedisConnection<String, String> connection = client.connect()) {
            RedisCommands<String, String> redis = connection.sync();
            redis.del(ParseStreamContract.REQUEST_STREAM, ParseStreamContract.RESULT_STREAM, ParseStreamContract.POLICY_KEY);
            ParseStreamProperties properties = new ParseStreamProperties(
                List.of(new ParseStreamProperties.Source("e2e", URL)),
                2, Duration.ofMillis(200), Duration.ofSeconds(5), Duration.ofSeconds(15), Duration.ofSeconds(60),
                Duration.ofMillis(100), Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofHours(1));
            ParseStreamConsumers consumers = new ParseStreamConsumers(
                properties, new LettuceParseStreamConnector(properties), new ParseRequestProcessor(null, null));
            consumers.start();
            try {
                redis.xadd(ParseStreamContract.REQUEST_STREAM, Map.of("requestId", "7", "inputType", "VIDEO", "inputValue", "x"));

                List<StreamMessage<String, String>> results = redis.xread(
                    XReadArgs.Builder.block(Duration.ofSeconds(10)),
                    XReadArgs.StreamOffset.from(ParseStreamContract.RESULT_STREAM, "0"));

                assertEquals(Map.of("requestId", "7", "code", "DELIVERY_EXHAUSTED"), results.getFirst().getBody());
            } finally {
                consumers.stop();
            }
            // 종료는 돌던 처리를 기다린 뒤라 확인 응답까지 끝나 있다
            assertEquals(0, redis.xlen(ParseStreamContract.REQUEST_STREAM));
            redis.del(ParseStreamContract.REQUEST_STREAM, ParseStreamContract.RESULT_STREAM);
        } finally {
            client.shutdown();
        }
    }
}
