package com.depromeet.piki.extractor.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.depromeet.piki.contracts.extraction.v1.ExtractionProto;
import com.depromeet.piki.extractor.extraction.HeadlessExtractionProperties;
import com.depromeet.piki.extractor.extraction.gemini.GeminiHttpClient;
import com.depromeet.piki.extractor.support.IntegrationTestSupport;
import com.google.protobuf.Descriptors.ServiceDescriptor;
import java.time.Duration;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * 계약 정본(TeamPiKi/infra 의 contracts/extraction.proto)의 service 선언과 이 서비스의 실물을 대조한다.
 * 경로는 어노테이션 상수라 계약에서 읽어 올 수 없어, 등록된 매핑을 계약과 비교한다.
 */
class ExtractionContractIntegrationTest extends IntegrationTestSupport {

    private static final ServiceDescriptor SERVICE =
        ExtractionProto.getDescriptor().findServiceByName("ExtractionService");

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Autowired
    private HeadlessExtractionProperties headlessProperties;

    @Test
    @DisplayName("POST /internal 매핑 집합은 계약의 rpc 경로 집합과 정확히 일치한다")
    void internalMappingsMatchContract() {
        Set<String> contractPaths = SERVICE.getMethods().stream()
            .map(method -> method.getOptions().getExtension(ExtractionProto.httpPost))
            .collect(Collectors.toCollection(TreeSet::new));
        Set<String> mappedPaths = handlerMapping.getHandlerMethods().keySet().stream()
            .filter(info -> info.getMethodsCondition().getMethods().contains(RequestMethod.POST))
            .flatMap(info -> info.getPatternValues().stream())
            .filter(path -> path.startsWith("/internal/"))
            .collect(Collectors.toCollection(TreeSet::new));

        assertEquals(contractPaths, mappedPaths);
    }

    /**
     * headless-first 최악은 렌더 connect + 렌더 read + LLM read 다. 이 합이 호출자 read 타임아웃을 넘으면 호출자가
     * 먼저 끊고 재시도해, 살아 있는 추출과 중복 발주가 겹친다. plain fetch 의 redirect 누적은 의도된 예외라 보지 않는다.
     */
    @Test
    @DisplayName("headless-first 내부 예산 합계는 계약의 호출자 read 타임아웃보다 작다")
    void headlessFirstBudgetFitsCallerTimeout() {
        Duration callerReadTimeout = Duration.ofSeconds(
            SERVICE.getOptions().getExtension(ExtractionProto.callerReadTimeoutSeconds));
        Duration budget = headlessProperties.connectTimeout()
            .plus(headlessProperties.readTimeout())
            .plusMillis(GeminiHttpClient.READ_TIMEOUT_MS);

        assertTrue(
            budget.compareTo(callerReadTimeout) < 0,
            "내부 예산 " + budget + " 이 호출자 read 타임아웃 " + callerReadTimeout + " 이상이다");
    }
}
