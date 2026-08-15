package com.depromeet.piki.extractor.common.exception;

import static org.junit.jupiter.api.Assertions.fail;

import com.depromeet.piki.extractor.common.storage.ImageStorageException;
import com.depromeet.piki.extractor.domain.ProductLinkException;
import com.depromeet.piki.extractor.domain.ProductSnapshotException;
import com.depromeet.piki.extractor.extraction.gemini.GeminiApiException;
import com.depromeet.piki.extractor.extraction.headless.HeadlessRenderException;
import com.depromeet.piki.extractor.extraction.http.PageFetchException;
import com.depromeet.piki.extractor.image.domain.ProductImageException;
import com.depromeet.piki.extractor.probe.ModelProbeException;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.yaml.snakeyaml.Yaml;

/**
 * 실패 code 계약을 infra 정본 카탈로그에 묶는 메타 테스트.
 *
 * <p>enum 과 계약 문서를 사람 손으로만 맞추면 한쪽만 고쳐도 아무것도 깨지지 않는다(실제로 core 쪽에서
 * 어긋남이 CI 초록불 상태로 발견됐다). 목록도 분류도 사람 판단이 낄 여지가 없어 기계로 가른다.
 *
 * <p>두 검사는 다른 질문에 답한다 — 하나는 "code 목록이 같은가", 다른 하나는 "그 code 의 분류가 실제
 * 동작과 같은가"다. 후자는 카탈로그 값을 예외 팩토리의 실물 플래그와 대조한다. 런타임 정본은 여전히
 * 팩토리이며(카탈로그가 판정 입력이 되면 정본이 둘이 된다) 카탈로그는 그 플래그의 계약 표기다.
 *
 * <p>Spring 컨텍스트를 띄우지 않으므로 단독 실행 가능하다
 * ({@code ./gradlew test --tests "com.depromeet.piki.extractor.common.exception.ExtractionErrorCodeCatalogTest"}).
 */
class ExtractionErrorCodeCatalogTest {

    /** 로컬은 install.sh 가, CI 는 ci.yml 의 checkout 스텝이 같은 경로에 놓는다 - 그래서 경로가 하나뿐이다. */
    private static final Path CATALOG = Path.of("shared-infra/contracts/extraction-error-codes.yaml");

    private static final String DISPOSITION = "disposition";
    private static final String ESCALATABLE = "escalatable";

    @Test
    @DisplayName("ExtractionErrorCode 상수 집합은 infra 카탈로그의 code 집합과 정확히 일치한다")
    void enumMatchesCatalog() {
        Set<String> catalogCodes = catalogEntries().keySet();
        Set<String> enumCodes = Arrays.stream(ExtractionErrorCode.values())
            .map(Enum::name)
            .collect(Collectors.toCollection(LinkedHashSet::new));

        // 단언 라이브러리의 집합 비교 대신 양방향 차집합을 직접 만든다 - 어느 쪽에 무엇이 없는지가 실패 메시지의
        // 전부인데, 한 번에 양쪽을 다 보여줘야 두 번 돌리지 않고 고칠 수 있다.
        Set<String> enumOnly = new TreeSet<>(enumCodes);
        enumOnly.removeAll(catalogCodes);
        Set<String> catalogOnly = new TreeSet<>(catalogCodes);
        catalogOnly.removeAll(enumCodes);

        if (enumOnly.isEmpty() && catalogOnly.isEmpty()) {
            return;
        }
        StringBuilder message = new StringBuilder("ExtractionErrorCode 와 infra 카탈로그(" + CATALOG + ")가 어긋난다.\n");
        if (!enumOnly.isEmpty()) {
            message.append("  enum 에만 있음 (카탈로그에 추가하라): ").append(String.join(", ", enumOnly)).append('\n');
        }
        if (!catalogOnly.isEmpty()) {
            message.append("  카탈로그에만 있음 (enum 에 추가했거나, 카탈로그에서 지워야 한다): ")
                .append(String.join(", ", catalogOnly)).append('\n');
        }
        fail(message.toString());
    }

    @Test
    @DisplayName("카탈로그의 disposition·escalatable 은 예외 팩토리가 실제로 세우는 플래그와 일치한다")
    void catalogFlagsMatchFactories() {
        Map<String, Map<String, Object>> catalog = catalogEntries();
        List<String> mismatches = new ArrayList<>();
        Set<String> covered = new TreeSet<>();

        for (FactoryCase factoryCase : factoryCases()) {
            ExtractionException exception = factoryCase.exception();
            String code = exception.code().name();
            covered.add(code);

            Map<String, Object> entry = catalog.get(code);
            if (entry == null) {
                // 목록 어긋남은 위 테스트가 지목한다. 여기서는 NPE 로 죽지 않게만 하고 넘어간다.
                continue;
            }

            String actualDisposition = exception.permanent() ? "permanent" : "transient";
            Object declaredDisposition = entry.get(DISPOSITION);
            if (!actualDisposition.equals(declaredDisposition)) {
                mismatches.add(code + " disposition: 카탈로그=" + declaredDisposition
                    + " / " + factoryCase.name() + "=" + actualDisposition);
            }

            // escalatable 은 fetch 경로에만 있는 축이라 카탈로그가 선언한 code 만 본다. 선언이 없는 code
            // (LLM·이미지·헤드리스·probe)에 이 검사를 강요하면 축 밖의 팩토리에 없는 값을 요구하게 된다.
            if (!entry.containsKey(ESCALATABLE)) {
                continue;
            }
            if (!(exception instanceof PageFetchException fetchException)) {
                mismatches.add(code + " escalatable: 카탈로그가 선언했으나 " + factoryCase.name()
                    + " 은 fetch 경로(PageFetchException)가 아니라 이 축을 갖지 않는다");
                continue;
            }
            Object declaredEscalatable = entry.get(ESCALATABLE);
            if (!Objects.equals(declaredEscalatable, fetchException.escalatable())) {
                mismatches.add(code + " escalatable: 카탈로그=" + declaredEscalatable
                    + " / " + factoryCase.name() + "=" + fetchException.escalatable());
            }
        }

        // 표에 없는 code 는 이 테스트가 아무것도 보증하지 않는다. code 만 늘고 대조가 안 늘면 커버리지가
        // 조용히 줄어들므로, 그 자체를 실패로 본다.
        Set<String> uncovered = new TreeSet<>(catalog.keySet());
        uncovered.removeAll(covered);
        if (!uncovered.isEmpty()) {
            mismatches.add("팩토리 표에 없어 분류가 무보증인 code (factoryCases 에 추가하라): "
                + String.join(", ", uncovered));
        }

        if (!mismatches.isEmpty()) {
            fail("카탈로그(" + CATALOG + ")와 예외 팩토리의 분류가 어긋난다.\n  " + String.join("\n  ", mismatches));
        }
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

            // probe 전용 code. 추출 경로가 아니라 escalatable 축 밖이고, 카탈로그도 scope: probe 로만 표시한다.
            new FactoryCase("ModelProbeException.notFound", ModelProbeException.notFound()),
            new FactoryCase("ModelProbeException.incompatible", ModelProbeException.incompatible())
        );
    }

    /**
     * 카탈로그의 code 별 속성 맵. 파일이 없어도 skip 하지 않고 실패시킨다 - skip 은 강제가 조용히 무너지는
     * 길이라, "정본을 못 읽었다" 는 어긋남과 똑같이 취급해야 한다.
     */
    private static Map<String, Map<String, Object>> catalogEntries() {
        if (!Files.isRegularFile(CATALOG)) {
            fail("infra 카탈로그를 찾지 못했다: " + CATALOG.toAbsolutePath()
                + "\n  로컬이면 infra 의 install.sh 를 실행하고, CI 면 ci.yml 의 'Checkout extraction contract' 스텝을 확인하라.");
        }
        Object root;
        try (Reader reader = Files.newBufferedReader(CATALOG)) {
            root = new Yaml().load(reader);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (!(root instanceof Map<?, ?> document) || !(document.get("codes") instanceof Map<?, ?> codes)) {
            return fail("카탈로그 형식이 어긋난다 - 최상위에 code 이름을 키로 갖는 codes 맵이 있어야 한다: " + CATALOG);
        }
        Map<String, Map<String, Object>> entries = new LinkedHashMap<>();
        codes.forEach((code, attributes) -> entries.put(String.valueOf(code), attributesOf(attributes)));
        return entries;
    }

    /** 속성 없는 code(값이 비어 있는 항목)도 목록 대조 대상이므로 빈 맵으로 받아 넘긴다. */
    private static Map<String, Object> attributesOf(Object attributes) {
        if (!(attributes instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        map.forEach((key, value) -> normalized.put(String.valueOf(key), value));
        return normalized;
    }
}
