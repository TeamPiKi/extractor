package com.depromeet.piki.extractor.support;

import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 테스트 컨벤션을 기계로 강제하는 메타 테스트.
 *
 * <p>PIKI-Extractor CLAUDE.md `## 테스트` 의 불변식 중 **오탐 없이 기계로 PASS/FAIL 을 가를 수 있는 것만**
 * 여기서 강제한다. 서비스 단독 테스트 여부·한국어 네이밍 적정성처럼 사람·모델 판단이 끼는 규칙은 산문(CLAUDE.md)에 둔다.
 *
 * <p>src/test/java 의 Java 소스를 **import 라인 기준**으로 스캔한다. import 기준이라
 * 이 파일이 문자열 리터럴로 들고 있는 금지 키워드 자체는 오탐하지 않는다.
 * Spring 컨텍스트를 띄우지 않으므로 단독 실행 가능하다
 * (`./gradlew test --tests "com.depromeet.piki.extractor.support.TestConventionTest"`).
 */
class TestConventionTest {

    private static final Path TEST_ROOT = Path.of("src/test/java");

    private record TestSource(String name, List<String> lines) {

        List<String> imports() {
            List<String> imports = new ArrayList<>();
            for (String line : lines) {
                String trimmed = line.trim();
                if (!trimmed.startsWith("import ")) {
                    continue;
                }
                String body = trimmed.substring("import ".length()).trim();
                // `import static a.b.C.method;` 의 static 과 뒤의 세미콜론을 떼어 순수 FQN 만 남긴다.
                if (body.startsWith("static ")) {
                    body = body.substring("static ".length()).trim();
                }
                if (body.endsWith(";")) {
                    body = body.substring(0, body.length() - 1).trim();
                }
                imports.add(body);
            }
            return imports;
        }

        boolean hasImportUnder(String prefix) {
            return imports().stream().anyMatch(imp -> imp.equals(prefix) || imp.startsWith(prefix + "."));
        }

        boolean hasAnyImportUnder(Collection<String> prefixes) {
            return prefixes.stream().anyMatch(this::hasImportUnder);
        }
    }

    private List<TestSource> testSources() {
        if (!Files.isDirectory(TEST_ROOT)) {
            throw new IllegalStateException("테스트 소스 루트를 찾지 못했다: " + TEST_ROOT.toAbsolutePath());
        }
        try (Stream<Path> paths = Files.walk(TEST_ROOT)) {
            return paths
                .filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".java"))
                .map(TestConventionTest::readSource)
                .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static TestSource readSource(Path path) {
        try {
            return new TestSource(path.getFileName().toString(), Files.readAllLines(path));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    @DisplayName("모킹 라이브러리와 컨텍스트 파괴 어노테이션은 테스트에서 import 되지 않는다")
    void noForbiddenImports() {
        Map<String, String> forbidden = new LinkedHashMap<>();
        forbidden.put("io.mockk", "mockk — 내부 모킹 금지. 외부 경계는 IntegrationStubs 의 프로그래머블 stub 으로 격리한다.");
        forbidden.put("com.ninjasquad.springmockk", "springmockk(@MockkBean/@SpykBean) — 내부 모킹 금지.");
        forbidden.put("org.mockito", "Mockito — 내부 모킹 금지.");
        forbidden.put("org.springframework.boot.test.mock.mockito", "@MockBean/@SpyBean — 빈 그래프를 바꿔 컨텍스트 캐시를 깬다.");
        forbidden.put("org.springframework.test.context.bean.override", "@MockitoBean/@TestBean — 컨텍스트 캐시를 깬다.");
        forbidden.put("org.springframework.test.annotation.DirtiesContext", "@DirtiesContext — 컨텍스트 캐시를 폭파한다.");
        forbidden.put("org.springframework.test.context.ActiveProfiles", "@ActiveProfiles — 다른 프로파일은 별도 컨텍스트로 캐싱된다.");
        forbidden.put("org.springframework.test.context.TestPropertySource", "@TestPropertySource — 클래스별 프로퍼티 변형은 별도 컨텍스트다.");

        List<String> violations = new ArrayList<>();
        for (TestSource src : testSources()) {
            for (Map.Entry<String, String> entry : forbidden.entrySet()) {
                if (src.hasImportUnder(entry.getKey())) {
                    violations.add(src.name() + ": import " + entry.getKey() + " (" + entry.getValue() + ")");
                }
            }
        }
        if (!violations.isEmpty()) {
            fail("테스트 컨벤션 위반 — 금지 import:\n" + String.join("\n", violations));
        }
    }

    @Test
    @DisplayName("@SpringBootTest 는 IntegrationTestSupport 한 곳에서만 선언된다")
    void springBootTestOnlyInSupport() {
        String springBootTest = "org.springframework.boot.test.context.SpringBootTest";
        List<String> offenders = new ArrayList<>();
        for (TestSource src : testSources()) {
            if (src.hasImportUnder(springBootTest) && !src.name().equals("IntegrationTestSupport.java")) {
                offenders.add(src.name());
            }
        }
        if (!offenders.isEmpty()) {
            fail(
                "@SpringBootTest 는 IntegrationTestSupport 에만 두고 통합 테스트는 이를 상속해야 한다 (단일 컨텍스트 공유). 위반: "
                    + String.join(", ", offenders));
        }
    }

    @Test
    @DisplayName("통합 테스트는 IntegrationTestSupport 를 상속한다")
    void integrationTestsExtendSupport() {
        List<String> offenders = new ArrayList<>();
        for (TestSource src : testSources()) {
            if (!src.name().endsWith("IntegrationTest.java")) {
                continue;
            }
            boolean extendsSupport = src.lines().stream().anyMatch(line -> line.contains("extends IntegrationTestSupport"));
            if (!extendsSupport) {
                offenders.add(src.name());
            }
        }
        if (!offenders.isEmpty()) {
            fail(
                "*IntegrationTest 는 IntegrationTestSupport 를 상속해야 컨텍스트 캐시를 공유한다. 위반: "
                    + String.join(", ", offenders));
        }
    }

    @Test
    @DisplayName("테스트는 @BeforeEach @BeforeAll 셋업 hook 을 쓰지 않는다")
    void noSetupHooks() {
        List<String> setupHooks = List.of(
            "org.junit.jupiter.api.BeforeEach",
            "org.junit.jupiter.api.BeforeAll");
        List<String> offenders = new ArrayList<>();
        for (TestSource src : testSources()) {
            if (src.hasAnyImportUnder(setupHooks)) {
                offenders.add(src.name());
            }
        }
        if (!offenders.isEmpty()) {
            fail(
                "셋업 hook(@BeforeEach/@BeforeAll) 금지 — 데이터·stub·MockMvc 는 각 테스트 본문에서 직접 만든다. 위반: "
                    + String.join(", ", offenders));
        }
    }

    @Test
    @DisplayName("E2E 테스트는 @Disabled 또는 @EnabledIf 어노테이션으로 CI 에서 격리한다")
    void e2eTestsIsolated() {
        // 무방비 E2E 는 CI 에서 실제 외부 호출(외부 쇼핑몰·Gemini)이 돌아 flaky·비용·차단을 유발한다.
        // import 존재가 아니라 클래스에 어노테이션이 실제로 붙었는지 본다 — import 만 하고 안 달면 무방비다.
        List<String> isolationAnnotations = List.of("@Disabled", "@EnabledIfEnvironmentVariable");
        List<String> offenders = new ArrayList<>();
        for (TestSource src : testSources()) {
            if (!src.name().endsWith("E2ETest.java")) {
                continue;
            }
            boolean isolated = src.lines().stream().anyMatch(line -> {
                String trimmed = line.trim();
                return isolationAnnotations.stream().anyMatch(trimmed::startsWith);
            });
            if (!isolated) {
                offenders.add(src.name());
            }
        }
        if (!offenders.isEmpty()) {
            fail(
                "*E2ETest 는 클래스에 @Disabled(\"이유\") 또는 @EnabledIfEnvironmentVariable 를 실제로 달아 CI 기본 실행에서 격리해야 한다 (import 만으로는 부족). 무방비 위반: "
                    + String.join(", ", offenders));
        }
    }
}
