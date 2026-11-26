package com.depromeet.piki.extractor.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.depromeet.piki.extractor.extraction.http.PageFetchException;
import com.depromeet.piki.extractor.stream.ParseStream.Abandoned;
import com.depromeet.piki.extractor.stream.ParseStream.Delivery;
import com.depromeet.piki.extractor.support.IntegrationTestSupport;
import com.depromeet.piki.extractor.support.StubGeminiClient;
import com.depromeet.piki.extractor.support.StubPageFetcher;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 전달 보장과 이어받기(TeamPiKi/infra contracts/extraction-api.md 8절)를 실제 추출 파이프라인으로 검증한다. 외부 경계인
 * Redis 스트림만 메모리 구현으로 바꾼다. 자리를 잃으면 실패가 아니라 멈춤으로 나타나 시간 상한을 건다.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class ParseStreamWorkerIntegrationTest extends IntegrationTestSupport {

    private static final String BLOCKED = "{requestId=42, code=BLOCKED_DOMAIN}";
    private static final String EXHAUSTED = "{requestId=42, code=DELIVERY_EXHAUSTED}";
    private static final Map<String, String> REQUEST =
        Map.of("requestId", "42", "inputType", "LINK", "inputValue", "https://shop.example.com/p/1");

    @Autowired
    private ParseRequestProcessor processor;

    @Autowired
    private StubPageFetcher stubPageFetcher;

    @Autowired
    private StubGeminiClient stubGeminiClient;

    private static ParseStreamProperties properties() {
        return new ParseStreamProperties(
            List.of(), 1, Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofSeconds(15), Duration.ofSeconds(60),
            Duration.ofSeconds(1), Duration.ofSeconds(30), Duration.ofSeconds(20), Duration.ofHours(1));
    }

    private ParseStreamWorker worker(MemoryParseStream stream) {
        return new ParseStreamWorker("test", stream, processor, properties(), Runnable::run);
    }

    private void fetchEndsBlocked() {
        stubGeminiClient.reset();
        stubPageFetcher.build = link -> {
            throw PageFetchException.blockedDomain();
        };
    }

    @Test
    @DisplayName("꺼낸 요청은 결과를 쓴 뒤에 확인 응답을 보낸다")
    void publishesResultBeforeAcknowledging() throws InterruptedException {
        MemoryParseStream stream = new MemoryParseStream();
        stream.incoming.add(new Delivery("1-0", REQUEST));
        fetchEndsBlocked();

        assertTrue(worker(stream).pollOnce());

        assertEquals(List.of("publish " + BLOCKED, "ack 1-0"), stream.log);
    }

    @Test
    @DisplayName("처리가 계약 밖 예외로 끝나면 결과도 확인 응답도 없이 남겨 다시 배달되게 한다")
    void leavesCrashedRequestPending() throws InterruptedException {
        MemoryParseStream stream = new MemoryParseStream();
        stream.incoming.add(new Delivery("1-0", REQUEST));
        stubGeminiClient.reset();
        stubPageFetcher.build = link -> {
            throw new IllegalStateException("버그");
        };

        worker(stream).pollOnce();

        assertTrue(stream.log.isEmpty());
    }

    @Test
    @DisplayName("결과를 쓰지 못하면 확인 응답을 보내지 않는다")
    void doesNotAcknowledgeWhenPublishFails() throws InterruptedException {
        MemoryParseStream stream = new MemoryParseStream();
        stream.incoming.add(new Delivery("1-0", REQUEST));
        stream.publishFails = true;
        fetchEndsBlocked();

        worker(stream).pollOnce();

        assertTrue(stream.log.isEmpty());
    }

    @Test
    @DisplayName("요청 번호를 읽지 못한 요청은 결과 없이 지운다")
    void dropsUnreadableRequest() throws InterruptedException {
        MemoryParseStream stream = new MemoryParseStream();
        stream.incoming.add(new Delivery("1-0", Map.of("inputType", "LINK")));

        worker(stream).pollOnce();

        assertEquals(List.of("ack 1-0"), stream.log);
    }

    @Test
    @DisplayName("처리가 어떻게 끝나든 자리를 돌려줘 다음 요청을 꺼낸다")
    void releasesPermitAfterEachOutcome() throws InterruptedException {
        MemoryParseStream stream = new MemoryParseStream();
        ParseStreamWorker worker = worker(stream);
        stream.incoming.add(new Delivery("1-0", REQUEST));
        stream.incoming.add(new Delivery("2-0", REQUEST));
        stream.incoming.add(new Delivery("3-0", REQUEST));
        stubGeminiClient.reset();
        stubPageFetcher.build = link -> {
            throw new IllegalStateException("버그");
        };
        worker.pollOnce();
        fetchEndsBlocked();
        stream.publishFails = true;
        worker.pollOnce();
        stream.publishFails = false;

        worker.pollOnce();

        assertEquals(List.of("publish " + BLOCKED, "ack 3-0"), stream.log);
    }

    @Test
    @DisplayName("읽기가 실패하면 false 를 돌려주고 자리를 돌려준다")
    void reportsReadFailure() throws InterruptedException {
        MemoryParseStream stream = new MemoryParseStream();
        ParseStreamWorker worker = worker(stream);
        stream.readFails = true;

        assertFalse(worker.pollOnce());

        stream.readFails = false;
        stream.incoming.add(new Delivery("1-0", REQUEST));
        fetchEndsBlocked();
        assertTrue(worker.pollOnce());
        assertEquals(List.of("publish " + BLOCKED, "ack 1-0"), stream.log);
    }

    @Test
    @DisplayName("실행기가 작업을 받지 않아도 자리를 돌려준다")
    void releasesPermitWhenExecutorRejects() throws InterruptedException {
        MemoryParseStream stream = new MemoryParseStream();
        ParseStreamWorker rejecting = new ParseStreamWorker("test", stream, processor, properties(), work -> {
            throw new RejectedExecutionException("종료 중");
        });
        stream.incoming.add(new Delivery("1-0", REQUEST));
        stream.abandoned.put("2-0", new Pending(1, REQUEST));

        assertFalse(rejecting.pollOnce());
        rejecting.reclaimOnce();

        // 자리를 잃었다면 여기서 멈춘다
        assertTrue(rejecting.pollOnce());
    }

    @Test
    @DisplayName("확인 응답 없이 남은 요청을 이어받아 다시 처리한다")
    void reclaimsAbandonedRequest() {
        MemoryParseStream stream = new MemoryParseStream();
        stream.abandoned.put("1-0", new Pending(1, REQUEST));
        fetchEndsBlocked();

        worker(stream).reclaimOnce();

        assertEquals(List.of("claim 1-0", "publish " + BLOCKED, "ack 1-0"), stream.log);
    }

    @Test
    @DisplayName("배달 상한까지 꺼내 간 요청은 추출하지 않고 실패 결과로 닫는다")
    void closesExhaustedRequestWithoutProcessing() {
        MemoryParseStream stream = new MemoryParseStream();
        stream.abandoned.put("1-0", new Pending(ParseStreamContract.MAX_DELIVERIES, REQUEST));
        stubPageFetcher.build = link -> {
            throw new AssertionError("배달 상한을 넘긴 요청을 추출했다");
        };

        worker(stream).reclaimOnce();

        assertEquals(List.of("claim 1-0", "publish " + EXHAUSTED, "ack 1-0"), stream.log);
    }

    @Test
    @DisplayName("이어받다 실패하거나 다른 소비자가 먼저 가져가도 자리를 돌려준다")
    void releasesPermitWhenClaimFailsOrIsLost() {
        MemoryParseStream stream = new MemoryParseStream();
        ParseStreamWorker worker = worker(stream);
        stream.abandoned.put("1-0", new Pending(1, REQUEST));
        stream.claimFails = true;
        worker.reclaimOnce();
        stream.claimFails = false;
        stream.claimLost = true;
        worker.reclaimOnce();
        stream.claimLost = false;
        fetchEndsBlocked();

        worker.reclaimOnce();

        assertEquals(List.of("claim 1-0", "publish " + BLOCKED, "ack 1-0"), stream.log);
    }

    @Test
    @DisplayName("정책을 읽지 못하면 직전 정책을 계속 쓴다")
    void keepsPreviousPolicyWhenUnreadable() throws InterruptedException {
        MemoryParseStream stream = new MemoryParseStream();
        ParseStreamWorker worker = worker(stream);
        stream.policy = () -> Optional.of("{\"version\": \"3\", \"identity\": \"PiKi/3\"}");
        worker.refreshPolicy();
        stream.policy = () -> {
            throw new IllegalStateException("Redis 에 닿지 못함");
        };
        worker.refreshPolicy();
        stream.incoming.add(new Delivery("1-0", REQUEST));
        fetchEndsBlocked();

        worker.pollOnce();

        assertEquals("PiKi/3", stubPageFetcher.lastIdentity.value());
    }

    @Test
    @DisplayName("정책 키가 없어지면 곧바로 기본값으로 돌아간다")
    void missingPolicyKeyResetsToDefault() throws InterruptedException {
        MemoryParseStream stream = new MemoryParseStream();
        ParseStreamWorker worker = worker(stream);
        stream.policy = () -> Optional.of("{\"version\": \"3\", \"identity\": \"PiKi/3\"}");
        worker.refreshPolicy();
        stream.policy = Optional::empty;
        worker.refreshPolicy();
        stream.incoming.add(new Delivery("1-0", REQUEST));
        fetchEndsBlocked();

        worker.pollOnce();

        assertTrue(stubPageFetcher.lastIdentity.isDefault());
    }

    @Test
    @DisplayName("해석하지 못하는 정책은 버리고 직전 정책을 계속 쓴다")
    void keepsPreviousPolicyWhenUnparsable() throws InterruptedException {
        MemoryParseStream stream = new MemoryParseStream();
        ParseStreamWorker worker = worker(stream);
        stream.policy = () -> Optional.of("{\"identity\": \"PiKi/3\"}");
        worker.refreshPolicy();
        stream.policy = () -> Optional.of("not json");
        worker.refreshPolicy();
        stream.incoming.add(new Delivery("1-0", REQUEST));
        fetchEndsBlocked();

        worker.pollOnce();

        assertEquals("PiKi/3", stubPageFetcher.lastIdentity.value());
    }

    private record Pending(long deliveries, Map<String, String> fields) {
    }

    private static final class MemoryParseStream implements ParseStream {

        final Deque<Delivery> incoming = new ArrayDeque<>();
        final Map<String, Pending> abandoned = new LinkedHashMap<>();
        final List<String> log = new ArrayList<>();
        boolean readFails;
        boolean publishFails;
        boolean claimFails;
        boolean claimLost;
        Supplier<Optional<String>> policy = () -> {
            throw new AssertionError("policy 를 세팅하지 않았다");
        };

        @Override
        public void ensureGroup() {
        }

        @Override
        public List<Delivery> readNew(Duration block) {
            if (readFails) {
                throw new IllegalStateException("Redis 에 닿지 못함");
            }
            Delivery next = incoming.poll();
            return next == null ? List.of() : List.of(next);
        }

        @Override
        public List<Abandoned> findAbandoned(Duration idle, int limit) {
            return abandoned.entrySet().stream().map(entry -> new Abandoned(entry.getKey(), entry.getValue().deliveries())).toList();
        }

        @Override
        public Optional<Delivery> claim(String id, Duration idle) {
            if (claimFails) {
                throw new IllegalStateException("Redis 에 닿지 못함");
            }
            if (claimLost) {
                return Optional.empty();
            }
            log.add("claim " + id);
            return Optional.of(new Delivery(id, abandoned.remove(id).fields()));
        }

        @Override
        public void publishResult(Map<String, String> fields) {
            if (publishFails) {
                throw new IllegalStateException("Redis 에 닿지 못함");
            }
            log.add("publish {requestId=" + fields.get("requestId") + ", code=" + fields.get("code") + "}");
        }

        @Override
        public void acknowledge(String id) {
            log.add("ack " + id);
        }

        @Override
        public Optional<String> readPolicy() {
            return policy.get();
        }

        @Override
        public void removeIdleConsumers(Duration idle) {
        }

        @Override
        public void stopReading() {
        }

        @Override
        public void close() {
        }
    }
}
