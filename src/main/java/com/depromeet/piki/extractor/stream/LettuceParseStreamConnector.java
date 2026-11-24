package com.depromeet.piki.extractor.stream;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
public class LettuceParseStreamConnector implements ParseStreamConnector {

    private final ParseStreamProperties properties;

    @Override
    public ParseStream connect(ParseStreamProperties.Source source, String consumerName) {
        return new LettuceParseStream(source.redisUrl(), consumerName, properties.commandTimeout());
    }
}
