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
    static final Duration PROCESSING_TIMEOUT = Duration.ofSeconds(REQUEST.getExtension(ExtractionProto.processingTimeoutSeconds));
    static final Duration REDELIVERY_SCAN_INTERVAL =
        Duration.ofSeconds(REQUEST.getExtension(ExtractionProto.redeliveryScanIntervalSeconds));

    static final String RESULT_STREAM = RESULT.getExtension(ExtractionProto.redisStream);
    static final String POLICY_KEY = ExtractionPolicy.getDescriptor().getOptions().getExtension(ExtractionProto.redisKey);

    static final String REQUEST_ID_FIELD = jsonName(ParseRequest.REQUEST_ID_FIELD_NUMBER);
    static final String INPUT_TYPE_FIELD = jsonName(ParseRequest.INPUT_TYPE_FIELD_NUMBER);
    static final String INPUT_VALUE_FIELD = jsonName(ParseRequest.INPUT_VALUE_FIELD_NUMBER);

    /** 계약은 이 값을 ParseResult.code 의 주석으로만 갖는다. */
    static final String CODE_OK = "OK";

    private ParseStreamContract() {
    }

    /**
     * 계약이 이 서비스에 지우는 시간 관계를 기동 때 본다. 어긋난 채로 돌면 아직 도는 요청을 다시 꺼내 가거나, 호출자가
     * 처리 중인 요청을 기한 초과로 닫는다.
     *
     * @param stageDeadline 이 시간이 지나면 새 단계를 시작하지 않는다.
     * @param longestStage 기한 직전에 시작해도 끝까지 가는 단계 가운데 가장 긴 것.
     * @param reclaimInterval 이어받을 요청을 찾는 주기.
     */
    static void requireHonored(Duration stageDeadline, Duration longestStage, Duration reclaimInterval) {
        if (stageDeadline.plus(longestStage).compareTo(PROCESSING_TIMEOUT) > 0) {
            throw new IllegalStateException(
                "추출 한 건의 최대 시간(" + stageDeadline.plus(longestStage) + ")이 계약의 processing_timeout_seconds 를 넘는다");
        }
        if (REDELIVERY_AFTER.compareTo(PROCESSING_TIMEOUT) <= 0) {
            throw new IllegalStateException("계약의 redelivery_after_seconds 가 processing_timeout_seconds 보다 커야 한다");
        }
        if (reclaimInterval.compareTo(REDELIVERY_SCAN_INTERVAL) > 0) {
            throw new IllegalStateException(
                "product.extract.stream.reclaim-interval 이 계약의 redelivery_scan_interval_seconds 보다 길다");
        }
    }

    private static String jsonName(int fieldNumber) {
        return ParseRequest.getDescriptor().findFieldByNumber(fieldNumber).getJsonName();
    }
}
