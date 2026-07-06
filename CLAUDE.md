# PIKI-Extractor 프로젝트 컨벤션

## 이 서비스의 정체

PIKI-Server(코틀린)에서 분리된 **상품 추출 서비스**다. 상품 URL(또는 S3 이미지)을 받아 fetch → 구조화 파싱(JSON-LD/OG) → LLM(Gemini) fallback → 정규화를 거쳐 추출 결과를 돌려준다.

- **무상태.** DB 없음, 호출 간 상태 없음. 상태를 넣고 싶어지면 설계 경고 신호다. 재시도·내구성·상태 전이는 전부 호출자(PIKI-Server outbox)의 몫이다.
- **소비자는 PIKI-Server 워커 하나뿐.** 공개 API 가 아니다. 보안그룹 내부망 전용, 인증 없음.
- **계약의 single source 는 `docs/api-contract.md`.** 응답은 3갈래뿐이다: 2xx(성공) / 422+code(확정 실패) / 그 외 전부(일시 실패). 진화는 additive-only, 배포는 Extractor 먼저.
- 차단 우회 **방법론**(스텔스·프록시·IP 전략)은 private repo(PIKI-HeadlessBrowser)에만 둔다. 이 repo(public)에는 "이 호스트는 헤드리스로 라우팅" 수준까지만 담는다.

## 언어: Java 25

- 모던 idiom 을 기본으로: `record`(값 객체·DTO), `sealed`(닫힌 분기), pattern matching `switch`. 로컬 변수 `var` 는 우변에서 타입이 자명할 때만.
- **이 repo 는 사람이 직접 읽고 이해하는 것을 우선한다.** 영리한 축약보다 평이하고 읽히는 코드. 포팅 시 Kotlin 원본의 의도를 보존하되 Java 다운 표현으로 옮긴다.

### Null 처리

PIKI-Server 의 Elvis 규칙에 대응하는 Java 규칙:

- 파라미터·필드의 non-null 강제는 `Objects.requireNonNull(x, "메시지")`.
- "없을 수 있음"을 반환할 땐 `Optional<T>` 또는 도메인 결과 타입(sealed). **null 반환 금지.**
- null 분기가 필요하면 guard clause 로 이른 반환:
  ```java
  if (value == null) return Extraction.miss(NO_DATA);
  ```
  (Kotlin 과 달리 Java 에선 `== null` guard 가 표준 idiom 이다 — 금지하지 않는다. 금지는 "null 을 흘려보내는 것"이다.)

## 예외 정책

판단 기준 한 줄은 PIKI-Server 와 같다: **"정상 호출로 여기 닿을 수 있나?"** 단, 이 서비스의 클라이언트는 PIKI-Server 워커다.

- 닿는다 → **계약** → `ExtractionException`(커스텀, code + 일시/확정 구분 내장) → 핸들러가 422 또는 502 로 매핑.
- 못 닿는다 → **불변식** → `IllegalStateException`/`IllegalArgumentException` → 일반 500. 호출자는 이를 "일시 실패"로 보고 bounded 재시도하므로 안전하다.
- 커스텀 예외는 private 생성자 + 정적 팩토리(사유 하나 = 팩토리 하나)로 만들고, code·일시/확정이 팩토리에 박힌다.
- 응답 body 에 내부 정보(대상 URL 원문·LLM 원문·스택)를 싣지 않는다. 디버깅 정보는 로그·cause 로.

## 로깅

- `private static final Logger log = LoggerFactory.getLogger(클래스.class);`
- URL 은 반드시 마스킹(`safeLogString` 포팅본: host+path만, 쿼리스트링 제외). 토큰·원문 HTML·LLM 응답 원문을 로그에 남기지 않는다.
- 레벨: info=정상 흐름·지표·호출자 계약 위반 / warn=외부(몰·Gemini·S3) 실패·에스컬레이션 실패·SSRF 차단 / error=서버 버그(스택 포함).
- SLF4J `{}` placeholder 사용, 문자열 연결 금지.

## 포팅 규율 (이관 기간 한정)

- **동작 등가(파리티)가 목표다.** Kotlin 원본의 분기·상수·메시지를 그대로 옮기고, 개선·리팩터링은 이관 완료 후 별도 작업으로 뺀다.
- 포팅한 클래스 상단 주석에 원본 경로를 남긴다: `// PIKI-Server: product/service/http/HttpPageFetcher.kt 포팅`.
- 하드코딩이던 상수(fetch 3MB cap·UA·타임아웃·LLM 200K char cap)는 `@ConfigurationProperties` 로 외부화하되 **기본값은 원본과 동일**하게 둔다.

## 테스트

PIKI-Server 테스트 컨벤션의 Java 번역판. 원문 규약이 더 자세하다 (PIKI-Server `.claude/rules/testing-convention.md`).

- **분류**: 단위(Spring 없이 분기 망라) / 통합(HTTP 진입 계약 검증, 외부는 stub) / E2E(실제 몰·Gemini 호출, env 격리). **DB 가 없으므로 Testcontainers·Docker 불필요** — `./gradlew test` 가 그냥 돈다.
- **모킹 금지, stub 우선.** Mockito/`@MockBean` 금지. 외부 경계(GeminiClient·PageFetcher·S3)는 `IntegrationStubs` 한 곳에 `@Primary` stub 빈으로 등록, default 람다는 throw.
- **단일 컨텍스트**: `@SpringBootTest` 는 `support/IntegrationTestSupport` 한 곳에만. 클래스별 `@Import`/`@TestPropertySource`/`@ActiveProfiles`/`@DirtiesContext` 금지.
- **셋업 hook 금지**: `@BeforeEach`/`@BeforeAll` 없이 각 테스트 본문이 자기 시나리오를 완결적으로 만든다.
- **네이밍**: 단위 `{대상}Test` / 통합 `{기능}IntegrationTest` / E2E `{대상}E2ETest`(반드시 `@Disabled` 또는 `@EnabledIfEnvironmentVariable` 격리). 메서드는 Java 라 backtick 불가 — **`@DisplayName` 에 한국어 한 문장**.
- 단언은 JUnit 5 `Assertions` 기본, 컬렉션·객체 그래프 비교만 AssertJ.
- 메타 테스트 `TestConventionTest`(금지 import·컨텍스트 규칙 기계 강제)는 파싱 포팅(3단계)과 함께 이식한다.

## 의존성

- 버전의 single source 는 `build.gradle.kts`. 문서에 버전 숫자를 박지 않는다.
- 새 의존성은 Maven Central 최신 안정 버전(pre-release 제외), Spring Boot BOM 관리 대상은 버전 명시 금지.
- PIKI-Server 와 공유하는 라인(jsoup·guava·AWS SDK·springdoc)은 그쪽과 버전을 맞춘다.

## 브랜치·PR

- 기본 브랜치 `main`. PR 은 `main` 을 향한다.
- PR 생성·갱신은 `/pr` 스킬로.
