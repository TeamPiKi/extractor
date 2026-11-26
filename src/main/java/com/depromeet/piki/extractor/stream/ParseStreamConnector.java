package com.depromeet.piki.extractor.stream;

/** 설정의 Redis 하나에 붙는 {@link ParseStream} 을 만든다. 연결은 첫 명령에서 맺는다. */
public interface ParseStreamConnector {

    ParseStream connect(ParseStreamProperties.Source source, String consumerName);
}
