package com.depromeet.piki.extractor.stream;

import com.depromeet.piki.contracts.extraction.v1.ExtractionPolicy;
import com.depromeet.piki.contracts.extraction.v1.ExtractionProto;
import com.depromeet.piki.contracts.extraction.v1.ParseRequest;
import com.depromeet.piki.contracts.extraction.v1.ParseResult;
import com.google.protobuf.DescriptorProtos.MessageOptions;
import java.time.Duration;

/** 스트림 경로의 이름과 시간 값. 정본은 TeamPiKi/infra 의 contracts/extraction.proto 의 메시지 옵션이다. */
final class ParseStreamContract {

    private static final MessageOptions REQUEST = ParseRequest.getDescriptor().getOptions();
    private static final MessageOptions RESULT = ParseResult.getDescriptor().getOptions();

    static final String REQUEST_STREAM = REQUEST.getExtension(ExtractionProto.redisStream);
    static final String REQUEST_GROUP = REQUEST.getExtension(ExtractionProto.redisConsumerGroup);
    static final Duration REDELIVERY_AFTER = Duration.ofSeconds(REQUEST.getExtension(ExtractionProto.redeliveryAfterSeconds));
    static final int MAX_DELIVERIES = REQUEST.getExtension(ExtractionProto.maxDeliveries);

    static final String RESULT_STREAM = RESULT.getExtension(ExtractionProto.redisStream);
    static final String POLICY_KEY = ExtractionPolicy.getDescriptor().getOptions().getExtension(ExtractionProto.redisKey);

    static final String REQUEST_ID_FIELD = jsonName(ParseRequest.REQUEST_ID_FIELD_NUMBER);
    static final String INPUT_TYPE_FIELD = jsonName(ParseRequest.INPUT_TYPE_FIELD_NUMBER);
    static final String INPUT_VALUE_FIELD = jsonName(ParseRequest.INPUT_VALUE_FIELD_NUMBER);

    /** 계약은 이 값을 ParseResult.code 의 주석으로만 갖는다. */
    static final String CODE_OK = "OK";

    private ParseStreamContract() {
    }

    private static String jsonName(int fieldNumber) {
        return ParseRequest.getDescriptor().findFieldByNumber(fieldNumber).getJsonName();
    }
}
