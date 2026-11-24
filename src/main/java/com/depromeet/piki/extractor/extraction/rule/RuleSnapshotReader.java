package com.depromeet.piki.extractor.extraction.rule;

import java.util.Optional;

/** 배포본 저장소에서 지금 값을 읽는다. 이 서비스의 외부 경계라 테스트는 stub 으로 격리한다. */
public interface RuleSnapshotReader {

    /**
     * @return 배포본의 JSON 문자열. 키가 없으면 empty — "규칙 0개" 라는 뜻이다.
     * @throws RuntimeException 저장소에 닿지 못했다. 키 없음과 뜻이 달라 예외로 구분한다.
     */
    Optional<String> read();
}
