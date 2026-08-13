package com.depromeet.piki.extractor.common.exception;

import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * 실패 code 목록을 infra 정본 카탈로그에 묶는 메타 테스트.
 *
 * <p>enum 과 계약 문서를 사람 손으로만 맞추면 한쪽만 고쳐도 아무것도 깨지지 않는다(실제로 core 쪽에서
 * 어긋남이 CI 초록불 상태로 발견됐다). 목록 일치는 사람 판단이 낄 여지가 없어 기계로 가른다.
 *
 * <p>대조 대상은 code 이름 집합뿐이다. 카탈로그가 함께 담는 disposition·bucket 은 검증용 메타데이터이며,
 * 일시/확정의 정본은 각 예외 팩토리의 permanent 플래그다(카탈로그가 런타임 판정 입력이 되면 정본이 둘이 된다).
 *
 * <p>Spring 컨텍스트를 띄우지 않으므로 단독 실행 가능하다
 * ({@code ./gradlew test --tests "com.depromeet.piki.extractor.common.exception.ExtractionErrorCodeCatalogTest"}).
 */
class ExtractionErrorCodeCatalogTest {

    /** 로컬은 install.sh 가, CI 는 ci.yml 의 checkout 스텝이 같은 경로에 놓는다 - 그래서 경로가 하나뿐이다. */
    private static final Path CATALOG = Path.of("shared-infra/contracts/extraction-error-codes.yaml");

    @Test
    @DisplayName("ExtractionErrorCode 상수 집합은 infra 카탈로그의 code 집합과 정확히 일치한다")
    void enumMatchesCatalog() {
        Set<String> catalogCodes = catalogCodes();
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

    /**
     * 카탈로그의 code 키 집합. 파일이 없어도 skip 하지 않고 실패시킨다 - skip 은 강제가 조용히 무너지는 길이라,
     * "정본을 못 읽었다" 는 어긋남과 똑같이 취급해야 한다.
     */
    private static Set<String> catalogCodes() {
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
        return codes.keySet().stream()
            .map(String::valueOf)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
