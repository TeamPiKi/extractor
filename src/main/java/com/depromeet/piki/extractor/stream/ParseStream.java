package com.depromeet.piki.extractor.stream;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 한 환경의 Redis 에서 요청을 꺼내고 결과와 정책을 주고받는다. 이 서비스의 외부 경계라 테스트는 stub 으로 격리한다.
 * 모든 메서드는 Redis 에 닿지 못하면 {@link RuntimeException} 을 던진다.
 */
public interface ParseStream {

    /** 소비 그룹이 없으면 만든다. 스트림이 통째로 사라진 뒤에도 이 호출로 되살린다. */
    void ensureGroup();

    /** 새 요청을 하나 꺼낸다. 없으면 {@code block} 만큼 기다린 뒤 빈 목록. */
    List<Delivery> readNew(Duration block);

    /** 확인 응답 없이 {@code idle} 넘게 남은 요청. */
    List<Abandoned> findAbandoned(Duration idle, int limit);

    /** 남은 요청을 내 이름으로 가져온다. 그 사이 다른 소비자가 가져갔거나 끝냈으면 empty. */
    Optional<Delivery> claim(String id, Duration idle);

    void publishResult(Map<String, String> fields);

    /** 확인 응답과 삭제. */
    void acknowledge(String id);

    /**
     * @return 정책 배포본의 JSON 문자열. 키가 없으면 empty — "전부 기본값" 이라는 뜻이다.
     */
    Optional<String> readPolicy();

    /**
     * 대기 목록이 비고 {@code idle} 넘게 읽지 않은 다른 소비자 이름을 그룹에서 지운다. 소비자 이름은 기동마다 새로 지어
     * 지우지 않으면 배포 횟수만큼 쌓인다. 대기 목록이 남은 이름은 지우지 않는다 — 지우면 그 요청이 사라진다.
     */
    void removeIdleConsumers(Duration idle);

    /** 새 요청 읽기만 끊는다. 돌던 처리의 결과 쓰기는 {@link #close} 전까지 된다. */
    void stopReading();

    void close();

    record Delivery(String id, Map<String, String> fields) {
    }

    /** @param deliveries 지금까지 꺼내 간 횟수. */
    record Abandoned(String id, long deliveries) {
    }
}
