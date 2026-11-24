package com.depromeet.piki.extractor.support;

import com.depromeet.piki.extractor.stream.ParseStream;
import com.depromeet.piki.extractor.stream.ParseStreamConnector;
import com.depromeet.piki.extractor.stream.ParseStreamProperties;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 통합 테스트가 개발자 로컬 설정(.env.local)의 실제 Redis 에 닿지 않게 막는다. 운영 구현처럼 붙는 것 자체는 실패하지
 * 않고 명령이 실패한다 — 설정이 있는 로컬에서도 컨텍스트는 뜬다.
 */
public class StubParseStreamConnector implements ParseStreamConnector {

    @Override
    public ParseStream connect(ParseStreamProperties.Source source, String consumerName) {
        return new Unreachable();
    }

    private static final class Unreachable implements ParseStream {

        private static IllegalStateException unreachable() {
            return new IllegalStateException("통합 테스트는 스트림에 붙지 않는다");
        }

        @Override
        public void ensureGroup() {
            throw unreachable();
        }

        @Override
        public List<Delivery> readNew(Duration block) {
            throw unreachable();
        }

        @Override
        public List<Abandoned> findAbandoned(Duration idle, int limit) {
            throw unreachable();
        }

        @Override
        public Optional<Delivery> claim(String id, Duration idle) {
            throw unreachable();
        }

        @Override
        public void publishResult(Map<String, String> fields) {
            throw unreachable();
        }

        @Override
        public void acknowledge(String id) {
            throw unreachable();
        }

        @Override
        public Optional<String> readPolicy() {
            throw unreachable();
        }

        @Override
        public void removeIdleConsumers(Duration idle) {
            throw unreachable();
        }

        @Override
        public void stopReading() {
        }

        @Override
        public void close() {
        }
    }
}
