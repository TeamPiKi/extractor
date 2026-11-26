package com.depromeet.piki.extractor.stream;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.Consumer;
import io.lettuce.core.Limit;
import io.lettuce.core.Range;
import io.lettuce.core.RedisBusyException;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisCommandExecutionException;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.XGroupCreateArgs;
import io.lettuce.core.XPendingArgs;
import io.lettuce.core.XReadArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Spring Data Redis 를 쓰지 않는 이유는 RedisRuleSnapshotReader 와 같다.
 *
 * <p>연결을 둘로 나눈다. 대기하는 읽기가 연결을 붙잡는 동안 같은 연결의 다른 명령이 그 뒤에 줄을 선다.
 */
final class LettuceParseStream implements ParseStream {

    private final ParseStreamProperties.Source source;
    private final Duration timeout;
    private final Consumer<String> consumer;
    private final Link reading = new Link();
    private final Link commanding = new Link();
    private RedisClient client;
    private boolean closed;

    LettuceParseStream(ParseStreamProperties.Source source, String consumerName, Duration timeout) {
        this.source = source;
        this.timeout = timeout;
        this.consumer = Consumer.from(ParseStreamContract.REQUEST_GROUP, consumerName);
    }

    @Override
    public void ensureGroup() {
        try {
            commanding.run(redis -> redis.xgroupCreate(
                XReadArgs.StreamOffset.from(ParseStreamContract.REQUEST_STREAM, "0"),
                ParseStreamContract.REQUEST_GROUP,
                XGroupCreateArgs.Builder.mkstream()
            ));
        } catch (RedisBusyException e) {
            // 이미 있다
        }
    }

    @Override
    public List<Delivery> readNew(Duration block) {
        List<StreamMessage<String, String>> messages = reading.run(redis -> redis.xreadgroup(
            consumer,
            XReadArgs.Builder.block(block).count(1),
            XReadArgs.StreamOffset.lastConsumed(ParseStreamContract.REQUEST_STREAM)
        ));
        return messages == null ? List.of() : messages.stream().map(LettuceParseStream::delivery).toList();
    }

    @Override
    public List<Abandoned> findAbandoned(Duration idle, int limit) {
        return commanding.run(redis -> redis.xpending(
                ParseStreamContract.REQUEST_STREAM,
                XPendingArgs.Builder.xpending(ParseStreamContract.REQUEST_GROUP, Range.unbounded(), Limit.from(limit))
                    .idle(idle)
            ))
            .stream()
            .map(pending -> new Abandoned(pending.getId(), pending.getRedeliveryCount()))
            .toList();
    }

    @Override
    public Optional<Delivery> claim(String id, Duration idle) {
        List<StreamMessage<String, String>> claimed = commanding.run(redis -> redis.xclaim(
            ParseStreamContract.REQUEST_STREAM, consumer, idle.toMillis(), id
        ));
        // 지워진 항목은 본문 없이 돌아온다
        return claimed.stream().filter(message -> message.getBody() != null).map(LettuceParseStream::delivery).findFirst();
    }

    @Override
    public void publishResult(Map<String, String> fields) {
        commanding.run(redis -> redis.xadd(ParseStreamContract.RESULT_STREAM, fields));
    }

    @Override
    public void acknowledge(String id) {
        commanding.run(redis -> redis.xackdel(ParseStreamContract.REQUEST_STREAM, ParseStreamContract.REQUEST_GROUP, id));
    }

    @Override
    public Optional<String> readPolicy() {
        return Optional.ofNullable(commanding.run(redis -> redis.get(ParseStreamContract.POLICY_KEY)));
    }

    @Override
    public void removeIdleConsumers(Duration idle) {
        List<Object> consumers = commanding.run(
            redis -> redis.xinfoConsumers(ParseStreamContract.REQUEST_STREAM, ParseStreamContract.REQUEST_GROUP));
        for (Object entry : consumers) {
            Map<String, Object> info = attributes(entry);
            String name = String.valueOf(info.get("name"));
            boolean removable = !name.equals(consumer.getName())
                && number(info.get("pending")) == 0
                && number(info.get("idle")) > idle.toMillis();
            if (removable) {
                commanding.run(redis -> redis.xgroupDelconsumer(
                    ParseStreamContract.REQUEST_STREAM, Consumer.from(ParseStreamContract.REQUEST_GROUP, name)));
            }
        }
    }

    @Override
    public void stopReading() {
        reading.close();
    }

    @Override
    public synchronized void close() {
        closed = true;
        reading.close();
        commanding.close();
        if (client != null) {
            client.shutdownAsync();
            client = null;
        }
    }

    private synchronized StatefulRedisConnection<String, String> connect() {
        // 종료가 시작된 뒤 도착한 명령이 클라이언트를 다시 만들어 남기지 않게 한다
        if (closed) {
            throw new IllegalStateException("종료 중이다");
        }
        if (client == null) {
            client = RedisClient.create(RedisURI.builder()
                .withHost(source.redisHost())
                .withPort(source.redisPort())
                .withAuthentication(source.redisUsername(), source.redisPassword().toCharArray())
                .build());
            client.setOptions(ClientOptions.builder()
                .socketOptions(SocketOptions.builder().connectTimeout(timeout).keepAlive(true).build())
                .timeoutOptions(TimeoutOptions.enabled(timeout))
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .build());
        }
        return client.connect();
    }

    private static Delivery delivery(StreamMessage<String, String> message) {
        return new Delivery(message.getId(), message.getBody());
    }

    /** XINFO 의 항목 하나는 프로토콜 판에 따라 이름·값이 번갈아 놓인 목록이거나 맵이다. */
    private static Map<String, Object> attributes(Object entry) {
        Map<String, Object> attributes = new HashMap<>();
        if (entry instanceof Map<?, ?> map) {
            map.forEach((key, value) -> attributes.put(String.valueOf(key), value));
        } else if (entry instanceof List<?> list) {
            for (int i = 0; i + 1 < list.size(); i += 2) {
                attributes.put(String.valueOf(list.get(i)), list.get(i + 1));
            }
        }
        return attributes;
    }

    /** 읽지 못한 값은 지우지 않는 쪽으로 떨어지게 한다. */
    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : -1;
    }

    private final class Link {

        private StatefulRedisConnection<String, String> connection;
        private boolean stopped;

        private <T> T run(Function<RedisCommands<String, String>, T> command) {
            StatefulRedisConnection<String, String> current = connection();
            try {
                return command.apply(current.sync());
            } catch (RedisCommandExecutionException e) {
                // Redis 가 답한 거절이라 연결은 멀쩡하다
                throw e;
            } catch (RuntimeException e) {
                // 끊긴 줄 모르는 연결은 명령마다 시간 초과만 낸다. 버리고 다음에 새로 맺는다
                discard(current);
                throw e;
            }
        }

        private synchronized StatefulRedisConnection<String, String> connection() {
            if (stopped) {
                throw new IllegalStateException("종료 중이다");
            }
            if (connection == null) {
                connection = connect();
            }
            return connection;
        }

        private synchronized void discard(StatefulRedisConnection<String, String> failed) {
            if (connection == failed) {
                connection.closeAsync();
                connection = null;
            }
        }

        private synchronized void close() {
            stopped = true;
            if (connection != null) {
                connection.closeAsync();
                connection = null;
            }
        }
    }
}
