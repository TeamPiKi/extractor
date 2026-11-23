package com.depromeet.piki.extractor.extraction.rule;

/** 지금 적용할 배포본. 요청마다 불리므로 구현은 바깥 저장소를 여기서 읽지 않는다. */
public interface MallRuleSource {

    MallRules current();
}
