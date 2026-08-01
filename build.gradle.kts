plugins {
    java
    id("org.springframework.boot") version "4.0.5"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.depromeet"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

// 이미지(OCR) 경로의 S3 raw 읽기·크롭 업로드용. 버전 라인은 core 와 동일하게 유지한다.
val awsSdkVersion = "2.44.11"

// 생성자 보일러플레이트 제거(@RequiredArgsConstructor) 전용 — 채택 범위·금지 애노테이션은 CLAUDE.md 참조.
val lombokVersion = "1.18.46"

dependencyManagement {
    imports {
        mavenBom("software.amazon.awssdk:bom:$awsSdkVersion")
    }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // OpenAPI 문서 노출(/v3/api-docs, UI 없음). 계약의 원문은 docs/api-contract.md.
    // Spring Boot 4 / Jackson 3 호환 라인 — core 와 동일.
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-api:3.0.3")

    // 상품 페이지 HTML 에서 구조화 데이터(JSON-LD <script>·OpenGraph <meta>)를 파싱한다.
    // core product 파이프라인 포팅 대상(그쪽 #425). Spring Boot BOM 미관리라 버전 명시.
    implementation("org.jsoup:jsoup:1.22.2")

    // redirect 추적 시 "같은 회사 도메인(eTLD+1)" 판정에 guava InternetDomainName(Public Suffix List)을 쓴다.
    // 점 개수 어림은 a.co.kr↔b.co.kr 을 같은 회사로 오판해 SSRF 구멍 (core #440 과 동일 근거).
    implementation("com.google.guava:guava:33.6.0-jre")

    // SSRF IP-pin: 가드가 검증한 IP 로만 연결하도록 DnsResolver 를 끼울 수 있는 HTTP 클라이언트.
    // JDK HttpURLConnection 은 IP pin 시 TLS SNI·인증서 수동 복구가 필요해 깨지기 쉽다. 버전은 Spring Boot BOM 관리.
    implementation("org.apache.httpcomponents.client5:httpclient5")

    // renderer(POST /render)의 압축 응답(X-Encoding: zstd) 해제 — 학습 사전(X-Zstd-Dict) 지원 포함.
    // Spring Boot BOM 미관리라 버전 명시.
    implementation("com.github.luben:zstd-jni:1.5.7-3")

    // 이미지(OCR) 경로: S3 raw 읽기 + 크롭 결과 업로드 (이관 6단계). 버전은 위 BOM 이 관리.
    implementation("software.amazon.awssdk:s3")

    // 관측: /actuator/health(상태)·/actuator/prometheus(Alloy scrape) + OTLP tracing.
    // 구성 근거(왜 OTel 3종인지)는 core build.gradle.kts 의 동일 블록 주석 참고.
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("org.springframework.boot:spring-boot-micrometer-tracing-opentelemetry")
    implementation("io.micrometer:micrometer-tracing-bridge-otel")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp")

    compileOnly("org.projectlombok:lombok:$lombokVersion")
    annotationProcessor("org.projectlombok:lombok:$lombokVersion")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testCompileOnly("org.projectlombok:lombok:$lombokVersion")
    testAnnotationProcessor("org.projectlombok:lombok:$lombokVersion")
}

tasks.withType<Test> {
    useJUnitPlatform()
    // zstd-jni 의 JNI 네이티브 로드 허용 — Java 25 는 경고만 내지만 미래 JDK 는 차단한다(JEP 472).
    // 런타임은 Dockerfile ENTRYPOINT 가 같은 플래그를 준다.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
