package com.depromeet.piki.extractor.common.exception;

import static org.junit.jupiter.api.Assertions.fail;

import com.depromeet.piki.contracts.extraction.v1.Disposition;
import com.depromeet.piki.contracts.extraction.v1.ExtractionProto;
import com.depromeet.piki.extractor.common.storage.ImageStorageException;
import com.depromeet.piki.extractor.domain.ProductLinkException;
import com.depromeet.piki.extractor.domain.ProductSnapshotException;
import com.depromeet.piki.extractor.extraction.gemini.GeminiApiException;
import com.depromeet.piki.extractor.extraction.headless.HeadlessRenderException;
import com.depromeet.piki.extractor.extraction.http.PageFetchException;
import com.depromeet.piki.extractor.image.domain.ProductImageException;
import com.depromeet.piki.extractor.probe.ModelProbeException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

/**
 * 실패 code 를 계약 정본(TeamPiKi/infra 의 contracts/extraction.proto)에 묶는 메타 테스트.
 *
 * <p>목록도 분류도 사람 손으로만 맞추면 한쪽만 고쳐도 아무것도 깨지지 않는다(core 쪽 어긋남이 CI 초록불 상태로
 * 발견된 적이 있다). 분류의 런타임 정본은 예외 팩토리의 플래그이고, 계약의 옵션은 그 표기다.
 *
 * <p>Spring 컨텍스트를 띄우지 않으므로 단독 실행 가능하다
 * ({@code ./gradlew test --tests "com.depromeet.piki.extractor.common.exception.ExtractionErrorCodeCatalogTest"}).
 */
class ExtractionErrorCodeCatalogTest {

    /**
     * 정적 fetch 실패를 렌더로 재시도하는 대상인지. extractor 내부 축이라 계약이 아니라 여기에 둔다.
     * BLOCKED_HOST 만 false 인 이유는 내부망 대상의 재시도 자체가 SSRF 라서다. 표에 없는 code 는 이 축 밖이다.
     */
    private static final Map<ExtractionErrorCode, Boolean> ESCALATABLE = Map.of(
        ExtractionErrorCode.EMPTY_SHELL, true,
        ExtractionErrorCode.FETCH_CLIENT_ERROR, true,
        ExtractionErrorCode.PERMANENT_UPSTREAM, true,
        ExtractionErrorCode.BLOCKED_HOST, false,
        ExtractionErrorCode.TOO_MANY_REDIRECTS, true,
        ExtractionErrorCode.MALFORMED_REDIRECT, true,
        ExtractionErrorCode.UPSTREAM_ERROR, true
    );

    @Test
    @DisplayName("ExtractionErrorCode 상수 집합은 계약 enum 의 code 집합과 정확히 일치한다")
    void enumMatchesContract() {
        // 핸들러가 도메인 code 이름으로 계약 enum 을 찾으므로, 목록이 어긋나면 그 code 의 422 가 500 으로 샌다.
        Set<String> contractCodes = Arrays.stream(com.depromeet.piki.contracts.extraction.v1.ExtractionErrorCode.values())
            .filter(value -> value != com.depromeet.piki.contracts.extraction.v1.ExtractionErrorCode.UNRECOGNIZED)
            .filter(value -> value.getNumber() != 0)
            .map(Enum::name)
            .collect(Collectors.toCollection(TreeSet::new));
        Set<String> enumCodes = Arrays.stream(ExtractionErrorCode.values())
            .map(Enum::name)
            .collect(Collectors.toCollection(TreeSet::new));

        Set<String> enumOnly = new TreeSet<>(enumCodes);
        enumOnly.removeAll(contractCodes);
        Set<String> contractOnly = new TreeSet<>(contractCodes);
        contractOnly.removeAll(enumCodes);
        if (enumOnly.isEmpty() && contractOnly.isEmpty()) {
            return;
        }
        fail("ExtractionErrorCode 와 계약 enum(extraction.proto)이 어긋난다.\n"
            + "  enum 에만 있음 (계약에 추가하라): " + String.join(", ", enumOnly) + "\n"
            + "  계약에만 있음 (enum 에 추가하라): " + String.join(", ", contractOnly));
    }

    @Test
    @DisplayName("계약의 disposition 은 예외 팩토리가 실제로 세우는 permanent 플래그와 일치한다")
    void dispositionMatchesFactories() {
        List<String> mismatches = new ArrayList<>();
        Set<String> covered = new TreeSet<>();

        for (FactoryCase factoryCase : factoryCases()) {
            ExtractionException exception = factoryCase.exception();
            String code = exception.code().name();
            covered.add(code);

            Disposition declared = declaredDisposition(code);
            Disposition actual = exception.permanent() ? Disposition.PERMANENT : Disposition.TRANSIENT;
            if (declared != actual) {
                mismatches.add(code + " disposition: 계약=" + declared + " / " + factoryCase.name() + "=" + actual);
            }
        }

        // 표에 없는 code 는 이 테스트가 아무것도 보증하지 않는다 - code 만 늘고 대조가 안 늘면 그 자체가 실패다.
        Set<String> uncovered = Arrays.stream(ExtractionErrorCode.values())
            .map(Enum::name)
            .collect(Collectors.toCollection(TreeSet::new));
        uncovered.removeAll(covered);
        if (!uncovered.isEmpty()) {
            mismatches.add("팩토리 표에 없어 분류가 무보증인 code (factoryCases 에 추가하라): "
                + String.join(", ", uncovered));
        }

        if (!mismatches.isEmpty()) {
            fail("계약(extraction.proto)과 예외 팩토리의 분류가 어긋난다.\n  " + String.join("\n  ", mismatches));
        }
    }

    @Test
    @DisplayName("fetch 경로 팩토리의 escalatable 은 선언된 표와 일치한다")
    void escalatableMatchesFactories() {
        List<String> mismatches = new ArrayList<>();

        for (FactoryCase factoryCase : factoryCases()) {
            ExtractionException exception = factoryCase.exception();
            Boolean declared = ESCALATABLE.get(exception.code());
            if (declared == null) {
                continue;
            }
            if (!(exception instanceof PageFetchException fetchException)) {
                mismatches.add(exception.code() + ": 표가 선언했으나 " + factoryCase.name()
                    + " 은 fetch 경로(PageFetchException)가 아니라 이 축을 갖지 않는다");
                continue;
            }
            if (declared != fetchException.escalatable()) {
                mismatches.add(exception.code() + ": 표=" + declared + " / " + factoryCase.name()
                    + "=" + fetchException.escalatable());
            }
        }

        if (!mismatches.isEmpty()) {
            fail("escalatable 표와 예외 팩토리가 어긋난다.\n  " + String.join("\n  ", mismatches));
        }
    }

    /** 이름이 계약에 없으면 여기서 터진다 - 목록 어긋남은 enumMatchesContract 가 먼저 지목한다. */
    private static Disposition declaredDisposition(String code) {
        return com.depromeet.piki.contracts.extraction.v1.ExtractionErrorCode.valueOf(code)
            .getValueDescriptor()
            .getOptions()
            .getExtension(ExtractionProto.disposition);
    }

    private record FactoryCase(String name, ExtractionException exception) {}

    /**
     * code 를 만드는 팩토리 전수. 리플렉션으로 긁지 않고 명시 호출로 두는 이유는, 팩토리 시그니처가 바뀌면
     * 런타임이 아니라 컴파일에서 깨져야 하기 때문이다.
     *
     * <p>한 code 를 여러 팩토리가 만들면 전부 넣는다(UPSTREAM_ERROR ← connect 실패·빈 body,
     * LLM_UPSTREAM ← 5xx·429·408·빈 응답 등). 같은 code 를 만드는 팩토리끼리 플래그가 갈리면 그 자체가
     * 문제 신호라, 표에 다 있어야 드러난다.
     */
    private static List<FactoryCase> factoryCases() {
        Throwable cause = new IllegalStateException("catalog contract test");
        return List.of(
            new FactoryCase("PageFetchException.upstreamError", PageFetchException.upstreamError(cause)),
            new FactoryCase("PageFetchException.unresolvableHost", PageFetchException.unresolvableHost(cause)),
            new FactoryCase("PageFetchException.emptyBody", PageFetchException.emptyBody()),
            new FactoryCase("PageFetchException.permanentUpstreamError", PageFetchException.permanentUpstreamError(cause)),
            new FactoryCase("PageFetchException.clientError", PageFetchException.clientError(cause)),
            new FactoryCase("PageFetchException.emptyShell", PageFetchException.emptyShell(cause)),
            new FactoryCase("PageFetchException.tooManyRedirects", PageFetchException.tooManyRedirects()),
            new FactoryCase("PageFetchException.redirectedToSiteRoot", PageFetchException.redirectedToSiteRoot()),
            new FactoryCase("PageFetchException.malformedRedirect", PageFetchException.malformedRedirect(cause)),
            new FactoryCase("PageFetchException.blockedHost", PageFetchException.blockedHost()),

            new FactoryCase("ProductSnapshotException.notProductPage", ProductSnapshotException.notProductPage()),
            new FactoryCase("ProductSnapshotException.untrustworthyValue", ProductSnapshotException.untrustworthyValue()),
            new FactoryCase("ProductSnapshotException.noExtractableContent", ProductSnapshotException.noExtractableContent()),

            new FactoryCase("ProductLinkException.blank", ProductLinkException.blank()),
            new FactoryCase("ProductLinkException.invalidFormat", ProductLinkException.invalidFormat(cause)),
            new FactoryCase("ProductLinkException.unsupportedScheme", ProductLinkException.unsupportedScheme()),

            new FactoryCase("GeminiApiException.upstreamError", GeminiApiException.upstreamError(cause)),
            new FactoryCase("GeminiApiException.emptyResponse", GeminiApiException.emptyResponse()),
            new FactoryCase("GeminiApiException.clientError", GeminiApiException.clientError(cause)),
            new FactoryCase("GeminiApiException.parseError", GeminiApiException.parseError(cause)),
            new FactoryCase("GeminiApiException.noTextPart", GeminiApiException.noTextPart()),
            // status 로 code 가 갈리는 유일한 팩토리라 양쪽 갈래를 다 넣는다 - 429·408 은 4xx 지만 일시다.
            new FactoryCase(
                "GeminiApiException.fromResponseError(500)",
                GeminiApiException.fromResponseError(new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR))),
            new FactoryCase(
                "GeminiApiException.fromResponseError(429)",
                GeminiApiException.fromResponseError(new HttpClientErrorException(HttpStatus.TOO_MANY_REQUESTS))),
            new FactoryCase(
                "GeminiApiException.fromResponseError(408)",
                GeminiApiException.fromResponseError(new HttpClientErrorException(HttpStatus.REQUEST_TIMEOUT))),
            new FactoryCase(
                "GeminiApiException.fromResponseError(400)",
                GeminiApiException.fromResponseError(new HttpClientErrorException(HttpStatus.BAD_REQUEST))),

            new FactoryCase("HeadlessRenderException.blocked", HeadlessRenderException.blocked()),
            new FactoryCase("HeadlessRenderException.upstream", HeadlessRenderException.upstream("test", cause)),

            new FactoryCase("ProductImageException.emptyImage", ProductImageException.emptyImage()),
            new FactoryCase("ProductImageException.unknownType", ProductImageException.unknownType()),
            new FactoryCase("ProductImageException.unsupportedType", ProductImageException.unsupportedType()),

            new FactoryCase("ImageStorageException.uploadFailed", ImageStorageException.uploadFailed(cause)),
            new FactoryCase("ImageStorageException.downloadFailed", ImageStorageException.downloadFailed(cause)),

            // probe 전용 code. 추출 경로가 아니라 escalatable 축 밖이다.
            new FactoryCase("ModelProbeException.notFound", ModelProbeException.notFound()),
            new FactoryCase("ModelProbeException.incompatible", ModelProbeException.incompatible())
        );
    }
}
