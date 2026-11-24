package com.depromeet.piki.extractor.extraction.rule;

import com.depromeet.piki.contracts.extraction.v1.ExtractionProto;
import com.depromeet.piki.contracts.extraction.v1.ExtractionRuleSet;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import jakarta.annotation.PreDestroy;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 운영 Redis 의 배포본 키를 읽는다. 키 이름의 정본은 TeamPiKi/infra 의 contracts/extraction.proto 다.
 *
 * <p>Spring Data Redis 를 쓰지 않는 이유는 자동 구성이 Redis 를 헬스체크에 묶기 때문이다. 이 서비스에서 Redis 는
 * 없어도 되는 의존이라, 닿지 못한다고 배포의 헬스체크가 떨어지면 안 된다.
 */
@Component
public class RedisRuleSnapshotReader implements RuleSnapshotReader {

    static final String KEY = ExtractionRuleSet.getDescriptor().getOptions().getExtension(ExtractionProto.redisKey);

    private final MallRuleProperties properties;
    private RedisClient client;
    private StatefulRedisConnection<String, String> connection;
    private boolean closed;

    public RedisRuleSnapshotReader(MallRuleProperties properties) {
        this.properties = properties;
    }

    @Override
    public synchronized Optional<String> read() {
        // 종료가 시작된 뒤 도착한 마지막 읽기가 클라이언트를 다시 만들어 남기지 않게 한다
        if (closed) {
            throw new IllegalStateException("종료 중이다");
        }
        try {
            return Optional.ofNullable(connection().sync().get(KEY));
        } catch (RuntimeException e) {
            // 끊긴 줄 모르는 연결(상대가 말없이 사라진 경우)은 명령마다 시간 초과만 낸다. 버리고 다음에 새로 맺는다
            closeConnection();
            throw e;
        }
    }

    private StatefulRedisConnection<String, String> connection() {
        if (client == null) {
            client = RedisClient.create(RedisURI.builder()
                .withHost(properties.redisHost())
                .withPort(properties.redisPort())
                .withAuthentication(properties.redisUsername(), properties.redisPassword().toCharArray())
                .build());
            client.setOptions(ClientOptions.builder()
                .socketOptions(SocketOptions.builder().connectTimeout(properties.timeout()).keepAlive(true).build())
                .timeoutOptions(TimeoutOptions.enabled(properties.timeout()))
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .build());
        }
        if (connection == null) {
            connection = client.connect();
        }
        return connection;
    }

    private void closeConnection() {
        if (connection != null) {
            connection.closeAsync();
            connection = null;
        }
    }

    @PreDestroy
    synchronized void shutdown() {
        closed = true;
        closeConnection();
        if (client != null) {
            client.shutdownAsync();
            client = null;
        }
    }
}
