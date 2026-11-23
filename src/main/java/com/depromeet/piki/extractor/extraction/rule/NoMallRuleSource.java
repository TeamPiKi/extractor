package com.depromeet.piki.extractor.extraction.rule;

import org.springframework.stereotype.Component;

/** 배포본을 읽는 구현이 붙기 전의 자리. Redis 구현이 들어오면 이 클래스는 지운다. */
@Component
public class NoMallRuleSource implements MallRuleSource {

    @Override
    public MallRules current() {
        return MallRules.NONE;
    }
}
