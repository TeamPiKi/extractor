package com.depromeet.piki.extractor.support;

import com.depromeet.piki.extractor.extraction.rule.RuleSnapshotReader;
import java.util.Optional;
import java.util.function.Supplier;

/** 통합 테스트가 개발자 로컬 설정(.env.local)의 실제 Redis 에 닿지 않게 막는다. */
public class StubRuleSnapshotReader implements RuleSnapshotReader {

    public Supplier<Optional<String>> build = () -> {
        throw new IllegalStateException("StubRuleSnapshotReader.build 를 세팅하지 않았다");
    };

    @Override
    public Optional<String> read() {
        return build.get();
    }
}
