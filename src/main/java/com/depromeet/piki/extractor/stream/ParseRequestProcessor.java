package com.depromeet.piki.extractor.stream;

import com.depromeet.piki.contracts.extraction.v1.ExtractionResult;
import com.depromeet.piki.contracts.extraction.v1.ParseRequest;
import com.depromeet.piki.contracts.extraction.v1.ParseResult;
import com.depromeet.piki.extractor.api.ExtractionResultMapper;
import com.depromeet.piki.extractor.common.exception.ExtractionException;
import com.depromeet.piki.extractor.domain.ProductLink;
import com.depromeet.piki.extractor.domain.ProductSnapshot;
import com.depromeet.piki.extractor.extraction.ExtractionDeadline;
import com.depromeet.piki.extractor.extraction.ProductLinkExtractor;
import com.depromeet.piki.extractor.image.ImageExtractionService;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 요청 한 건을 추출해 결과 항목의 필드로 만든다. 추출 규칙은 HTTP 경로(ExtractionController)와 같고, 요청에 실리던
 * 정책 값만 배포본에서 온다.
 *
 * <p>계약 실패가 아닌 예외(불변식 위반·버그)는 일부러 잡지 않는다. 결과 없이 남아 한 번 더 배달되고, 그래도 끝나지 않으면
 * 배달 상한이 닫는다.
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class ParseRequestProcessor {

    private final ProductLinkExtractor productLinkExtractor;
    private final ImageExtractionService imageExtractionService;

    /** @return 요청 번호를 읽지 못해 결과를 쓸 수 없으면 empty. */
    Optional<Map<String, String>> process(Map<String, String> request, ExtractionPolicySnapshot policy, String source) {
        OptionalLong requestId = requestIdOf(request);
        if (requestId.isEmpty()) {
            return Optional.empty();
        }
        try {
            ProductSnapshot snapshot = extract(requestId.getAsLong(), request, policy, source);
            return Optional.of(succeeded(requestId.getAsLong(), ExtractionResultMapper.from(snapshot)));
        } catch (ExtractionException e) {
            logFailure(requestId.getAsLong(), source, e);
            return Optional.of(failed(requestId.getAsLong(), e));
        }
    }

    /** 배달 상한을 넘겨 추출하지 않고 닫는 요청의 결과. 요청 번호를 읽지 못하면 empty. */
    Optional<Map<String, String>> exhausted(Map<String, String> request, String source) {
        OptionalLong requestId = requestIdOf(request);
        if (requestId.isEmpty()) {
            return Optional.empty();
        }
        ParseRequestException failure = ParseRequestException.deliveryExhausted();
        logFailure(requestId.getAsLong(), source, failure);
        return Optional.of(failed(requestId.getAsLong(), failure));
    }

    private ProductSnapshot extract(long requestId, Map<String, String> request, ExtractionPolicySnapshot policy, String source) {
        String inputValue = request.getOrDefault(ParseStreamContract.INPUT_VALUE_FIELD, "");
        String inputType = request.getOrDefault(ParseStreamContract.INPUT_TYPE_FIELD, "");
        if (ParseRequest.InputType.LINK.name().equals(inputType)) {
            return extractLink(requestId, inputValue, policy, source);
        }
        if (ParseRequest.InputType.IMAGE.name().equals(inputType)) {
            return extractImage(requestId, inputValue, policy, source);
        }
        throw ParseRequestException.unknownInputType();
    }

    private ProductSnapshot extractLink(long requestId, String url, ExtractionPolicySnapshot policy, String source) {
        ProductLink link = ProductLink.parse(url);
        boolean authorized = policy.authorizedDomains().authorizes(link);
        // 추출 품질이나 차단이 흔들릴 때 "그때 어느 값이었나"를 되짚는 유일한 근거라 정책 값을 원장에 남긴다
        log.info(
            "extract request source={} requestId={} policyVersion={} authorized={} model={} identity={} blockedDomains={} url={}",
            source,
            requestId,
            policy.version(),
            authorized,
            policy.linkModel(),
            policy.identity(),
            policy.blockedDomains().values().size(),
            link.safeLogString()
        );
        return ExtractionDeadline.withinLinkBudget(() -> productLinkExtractor.extract(
            link, authorized, policy.linkModel(), policy.identity(), policy.blockedDomains()));
    }

    private ProductSnapshot extractImage(long requestId, String key, ExtractionPolicySnapshot policy, String source) {
        if (!policy.hasImageBucket()) {
            throw ParseRequestException.imageBucketMissing();
        }
        log.info(
            "image extract request source={} requestId={} policyVersion={} bucket={} key={} model={}",
            source,
            requestId,
            policy.version(),
            policy.imageBucket(),
            key,
            policy.imageModel()
        );
        return imageExtractionService.extract(policy.imageBucket(), key, policy.imageModel());
    }

    private static OptionalLong requestIdOf(Map<String, String> request) {
        try {
            return OptionalLong.of(Long.parseLong(request.getOrDefault(ParseStreamContract.REQUEST_ID_FIELD, "")));
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }

    /** 계약의 같은 이름 필드로 옮긴다. 필드가 늘어도 여기서 컴파일이 깨지지 않아 ParseRequestProcessorIntegrationTest 가 대조한다. */
    private static Map<String, String> succeeded(long requestId, ExtractionResult extracted) {
        ParseResult.Builder result = ParseResult.newBuilder()
            .setRequestId(requestId)
            .setCode(ParseStreamContract.CODE_OK)
            .setMethod(extracted.getMethod());
        if (extracted.hasName()) {
            result.setName(extracted.getName());
        }
        if (extracted.hasImageUrl()) {
            result.setImageUrl(extracted.getImageUrl());
        }
        if (extracted.hasCurrentPrice()) {
            result.setCurrentPrice(extracted.getCurrentPrice());
        }
        if (extracted.hasOriginalPrice()) {
            result.setOriginalPrice(extracted.getOriginalPrice());
        }
        if (extracted.hasCurrency()) {
            result.setCurrency(extracted.getCurrency());
        }
        if (extracted.hasFinalUrl()) {
            result.setFinalUrl(extracted.getFinalUrl());
        }
        return fieldsOf(result.build());
    }

    private static Map<String, String> failed(long requestId, ExtractionException failure) {
        return fieldsOf(ParseResult.newBuilder().setRequestId(requestId).setCode(failure.code().name()).build());
    }

    private static Map<String, String> fieldsOf(ParseResult result) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (Map.Entry<FieldDescriptor, Object> entry : result.getAllFields().entrySet()) {
            Object value = entry.getValue();
            fields.put(
                entry.getKey().getJsonName(),
                value instanceof EnumValueDescriptor enumValue ? enumValue.getName() : value.toString()
            );
        }
        return fields;
    }

    /** 레벨과 스택을 싣는 기준은 ExtractionExceptionHandler 와 같다. */
    private static void logFailure(long requestId, String source, ExtractionException e) {
        if (e.permanent()) {
            log.info("extraction failed permanently source={} requestId={} code={} message={}",
                source, requestId, e.code(), e.getMessage());
        } else if (e.getCause() != null) {
            log.warn("extraction failed transiently source={} requestId={} code={} message={}",
                source, requestId, e.code(), e.getMessage(), e);
        } else {
            log.warn("extraction failed transiently source={} requestId={} code={} message={}",
                source, requestId, e.code(), e.getMessage());
        }
    }
}
