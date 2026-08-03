# 스타일 결정 기록 — 우아한테크코스 스타일 검토 (2026-07-20)

참고 기준: [woowacourse-teams/2025-estime `be/dev`](https://github.com/woowacourse-teams/2025-estime/tree/be/dev) 백엔드.
목표: 우테코식 객체지향 스타일을 참고하되, 이 서비스의 정체(무상태·단일 소비자·포팅 파리티 — CLAUDE.md 참조)에 맞는 형태로 취사선택한다. 아래 결정이 이 repo 스타일의 기준선이며, CLAUDE.md 와 충돌하면 CLAUDE.md 가 우선한다.

## 1. estime 대비 채택 / 기각 결정

| # | 관례 (estime) | 결정 | 근거 |
|---|---|---|---|
| 1 | 4모듈 헥사고날(core/application/infrastructure/api) | **기각** | 무상태·DB 없음·소비자 1개 서비스에 모듈 분리는 과설계. 대신 포트-어댑터의 핵심(인터페이스 경계: `PageFetcher`·`HeadlessRenderer`·`GeminiClient`·`ImageStorage`)은 이미 단일 모듈 안에서 성립해 있다 — 이 경계를 유지·강화하는 것으로 같은 효과를 얻는다. |
| 2 | rich domain + 정적 팩토리(`withoutId`/`from`/`of`) + 생성 시점 검증, public 생성자 금지 | **이미 부합 — 유지** | `ProductLink.parse`·`ProductImage.of`·`ProductSnapshot.fromExtracted` 가 같은 철학. 팩토리 명명은 현행(`parse`=문자열 해석, `of`=성분 조립, `from~`=다른 표현 변환)을 유지한다. |
| 3 | 값 객체를 Lombok 클래스로 | **기각 — record 유지** | estime 의 클래스 VO 는 JPA 제약(no-arg 생성자) 대응이 크다. JPA 없는 이 repo 에선 `record` 가 더 정확한 도구다. |
| 4 | Lombok 전면(@Getter·@RequiredArgsConstructor·@Slf4j…) | **보일러플레이트 표적 채택** (2026-07-23 재결정) | 사용자 피드백("생성자 직접 작성을 극도로 꺼림", "로거도 전부 애노테이션으로")으로 전면 기각 → 두 애노테이션만 채택. **`@RequiredArgsConstructor`**: 순수 필드-대입 생성자 12개 클래스 전환(@Qualifier 는 필드 + lombok.config copyableAnnotations), 조립 로직 생성자 4개는 손 유지(HttpPageFetcher·HttpHeadlessRenderer·GeminiHttpClient — 파생 필드/클라이언트 빌드, RequestScopedDnsResolver — 편의 생성자 2개). **`@Slf4j`**: 명시적 Logger 선언 11개 전환 — 생성 결과가 `private static final Logger log = LoggerFactory.getLogger(자기클래스.class)` 와 바이트코드 동일(javap 로 확인)이라 로거 이름·로그 출력 변화 없음. 값 객체는 여전히 record, @Builder·@Setter·@Data·@Value·@Getter 는 미채택. 애노테이션 순서는 Lombok → Spring. |
| 5 | jakarta.validation 미사용, 검증은 100% 도메인 책임 | **이미 부합 — 유지** | url 형식 검증을 Bean Validation 이 아니라 `ProductLink.parse` 가 맡는 현행 구조가 정확히 같은 철학. |
| 6 | 사유별 예외 클래스 + 이중 메시지(log 영어/user 한국어) + HTTP 200 고정 `CustomApiResponse` 봉투 | **기각** | 소비자가 사람이 아니라 PIKI-Server 워커다. HTTP status 가 계약의 전이 신호(2xx/422/기타)라 200 고정 봉투는 계약 파괴. userMessage 도 무의미. 단 estime 의 "사유별 예외" 의도는 우리 "사유 하나 = 정적 팩토리 하나" 규칙이 이미 담고 있다. |
| 7 | 초박형 컨트롤러 + Swagger 스펙 인터페이스 분리 | **절반 부합** | 초박형 컨트롤러는 이미 부합. 스펙 인터페이스 분리는 기각 — 계약 SSOT 가 `docs/api-contract.md` 고 소비자가 하나라 Swagger 문서화 계층이 필요 없다. |
| 8 | 일급 컬렉션(`Participants`·`Votes`) | **보류(사례 발생 시 채택)** | 현재 컬렉션 불변식을 가진 도메인 개념이 없다. 생기면 이 패턴을 쓴다. |
| 9 | `TimeProvider` 포트로 시간 주입, 도메인은 `now` 파라미터 수령 | **보류(사례 발생 시 채택)** | 현재 시간 의존 도메인 로직이 없다. 생기면 `Instant.now()` 직접 호출 대신 이 패턴을 쓴다. |
| 10 | 파라미터·로컬변수 `final` 전면 | **기각** | 시그니처 노이즈 대비 이득이 작고 repo 관례가 아니다. 불변은 record·불변 컬렉션으로 표현한다. |
| 11 | 주석 최소주의(151개 파일 중 Javadoc 6개) | **기각 — "왜" Javadoc 자산 유지** | 이 repo 의 "왜" 주석은 CLAUDE.md 가 자산으로 선언한 차별점이다(포팅 근거·계약 경계·보안 근거). estime 방향으로 줄이지 않는다. 단 시그니처 재진술 `@param` 같은 "무엇" 주석은 노이즈로 제거한다(§2). |
| 12 | 테스트: @DisplayName 한국어 · AssertJ 전면 · Mockito 계층 · @BeforeEach 적극 | **부분 채택(이미 부합) / 나머지 기각** | @DisplayName 한국어 한 문장은 이미 규약. Mockito 는 기각(stub 우선 규율이 estime 보다 강하고 유지 가치가 있다). @BeforeEach 금지 유지(각 테스트 자기완결). AssertJ 는 현행대로 컬렉션·객체 그래프 비교에만. given-when-then 주석은 강제하지 않되 긴 테스트에서 허용. |
| 13 | 메서드 명명: `validate~`(생성 시)·`ensure~`(사용 시)·`obtain~`(찾고 없으면 throw)·`markAs~`(상태전이) | **채택(새 코드 기준)** | 검증 계열 명명의 일관성은 가져갈 가치가 있다. 단 기존 코드 일괄 개명은 하지 않는다 — 포팅 파리티 기간엔 새로 쓰는 코드부터 적용. |

## 2. 이번 /code-review 결과의 결정

xhigh 리뷰(파인더 10 앵글 → 검증 → 스윕)로 확정 15건 + 백로그 후보를 얻었고, 아래처럼 처리했다.

> **2026-07-23 갱신**: 첫 스타일 패스 워킹트리는 폐기됐고, 그 뒤 ① Lombok `@RequiredArgsConstructor` 전환(§1-4)과 ② 주석 전환 재작업(§3)이 순서대로 적용됐다. 아래 표 중 **주석에 관한 항목**(javadoc 태스크 수정·SSOT 정리·중복 제거·왜-주석 보존)은 §3 에서 되살아나 현재 코드에 살아 있다. 반면 **코드를 건드리는 항목**은 §3 이 주석 전용 패스였으므로 아직 미반영이다 — `ProductImage` 파생 역맵(§4-4), `bytes()` 이중 방어 복사 제거·`maskUrls` 정규식 사전 컴파일, Gemini 타임아웃·`max-llm-chars` 의 `@ConfigurationProperties` 외부화. 이 셋은 다음 코드 작업의 후보다.

### 적용 완료

| 분류 | 내용 |
|---|---|
| 컴파일 깨짐 | `HttpHeadlessRenderer` 의 `restClient` 필드 선언이 주석 전환 중 삭제 → 복구 |
| javadoc 태스크 실패 | 미이스케이프 `<...>` 2곳(`HeadlessExtractionProperties.baseUrl`·`ZstdDictionaries`)과 Javadoc 본문 줄머리 `@DefaultValue` 오파싱(`GeminiProperties.Retry`) → `{@code}` 로 수정. `./gradlew javadoc` 통과 확인 |
| 왜-주석 복원 | `GeminiHtmlExtractorE2ETest` 생존성 테스트의 근거 문장(인증·스키마·직렬화·모델) 복원 |
| SSOT 주석 정리 완결 | 반쪽 적용됐던 정리를 전수 마감: `HeadlessRenderException` 의 attempt 상한(2) 2곳, `FetchProperties` 3MB, `HeadlessExtractionProperties` 55s/30s/실측치, `GeminiRetry` 재시도 대상 열거, `ExtractionErrorCode` 4개 상수의 확정/일시 분류(정본=예외 팩토리 permanent 로 포인터화) |
| Javadoc 중복·재진술 제거 | 시그니처 재진술 `@param`·클래스 Javadoc 과의 이중 기술 8곳(`LinkExtractionRequest`·`ImageExtractionRequest`·`S3Properties`·`StoredImage`·`ImageExtraction`·`ExtractionFailureResponse`·`ImageStorageException` 생성자·`GeminiImageRequest` ofType/MINIMAL_THINKING) |
| 파생으로 단일화 | `ProductImage.mimeTypeOfExtension` 의 하드코딩 switch 를 `MIME_TO_EXTENSION` 파생 역맵으로 교체("jpeg" 별칭만 명시 유지) — "포맷 추가는 한 곳" 약속 복원 |
| 미세 효율 | `ImageExtractionService.croppedOrOriginal` 의 `bytes()` 이중 방어 복사 제거, `HttpHeadlessRenderer.maskUrls` 의 정규식 사전 컴파일(형제 클래스 관례 정합) |
| 상수 외부화(포팅 규율 명시 대상) | `gemini.connect-timeout(5s)`·`read-timeout(30s)`·`max-llm-chars(200000)` 를 `GeminiProperties` 로 — 기본값은 원본과 동일, 기존 3-인자 편의 생성자를 유지해 호출부 호환. `sanitize` 는 순수성 유지를 위해 상한을 파라미터로 받는다 |

### 백로그 (기록만 — 별도 작업으로)

1. **sanitize 주석 제거의 DOM 전환**: 현재 regex(`<!--.*?-->`) 방식은 보존 대상 JSON-LD·data island **내부의** `<!-- -->` 리터럴까지 지워 JSON 을 오염시킬 수 있는 잠재 버그이며, 3MB 급 문자열을 이중 할당한다. jsoup Comment 노드 제거로 바꾸면 둘 다 해소되지만 의도적 동작 변화라 전용 테스트(보존 아일랜드 내 주석 리터럴)와 함께 간다. 이 repo 가 파싱 정본이 된 지금 파리티 규율이 막지 않는다.
2. **zstd 해제 스트리밍 파싱**: `readNBytes` 전체 실체화(~2x 일시 할당) 대신 길이 제한 스트림 위 `readValue`. 단 상한 초과는 truncate 가 아니라 throw 여야 하고(실패 분류 보존), 관측 로그의 바이트 카운트·오류 귀속(해제 실패 vs 파싱 실패)이 load-bearing 이라 신중히.
3. **ProductImage 바이트 소유권 재설계**: 요청당 원본 바이트가 4~5회 복사된다(방어 복사 설계의 대가). 소유권 이전 방식은 설계 변경이라 별도 논의.
4. **S3Config apiCallTimeout(10s/5s) 외부화**: 포팅 규율의 명시 대상인지 애매 — 필요해지면 `S3Properties` 로.

### 기각·불복원 판정

- `GeminiPropertiesTest` 의 preview 모델 주석 삭제: **문제없음(REFUTED)** — 정본이 `GeminiProperties.DEFAULT_MODEL` Javadoc 에 살아 있어 삭제가 SSOT 규칙 준수다.
- `GeminiHtmlExtractorTest` 단언별 꼬리 주석 4개: **복원 안 함** — @DisplayName 과 본문 왜-주석이 이미 커버하는 "무엇" 라벨로 판정.

### 이번 리뷰에서 증류된 원칙 (재발 방지)

- **SSOT 주석 정리는 반쪽 적용이 더 나쁘다** — 한 곳에서 수치를 지웠으면 같은 수치를 전수 grep 으로 마감한다(남은 쪽이 "정리된 척하는 낡은 주석"이 된다).
- **왜-주석 삭제 전 정본 확인** — 같은 근거가 선언부 Javadoc 등 정본에 있으면 삭제(SSOT 준수), 없으면 보존("왜 주석은 자산").
- **주석 일괄 전환 후엔 `./gradlew javadoc` 을 게이트로** — raw `<...>`·줄머리 `@애노테이션` 이 조용히 문서를 깨뜨린다.

## 3. 주석 전환 (2026-07-23 적용 완료)

전 소스 105개 파일(main 63 · test 42)의 주석을 CLAUDE.md "### 주석" 규칙으로 전환했다. 기준은 **"숙련 Java 독자가 이 주석 없이 놓칠 정보가 있는가"** 하나이고, 애매한 중간 지대는 지우는 쪽을 택했다.

- **결과**: 라인주석 827 → 142 (-83%), Javadoc 블록 5 → 194, 97개 파일 변경. main 의 트레일링 주석 0건.
- **남은 라인주석 142개**는 전부 메서드 본문의 "왜"다 — 실제 사이트 지식(유니클로 `| UNIQLO KR` 꼬리표와 `lastIndexOf` 인 이유, 29cm `contentUrl`, 카카오 charset 누락), Java 함정(`intValue` wrap vs `intValueExact`, `IllegalCharsetNameException` 계층, Jackson 3 `FAIL_ON_NULL_FOR_PRIMITIVES`), 계약·보안 근거(IP pin 은 같은 resolver 인스턴스로만 성립, 매 hop SSRF 재검증, 메트릭 집계 누락 방지를 위한 `Throwable` catch).
- **javadoc 태스크를 고쳤다**: 전환 전 HEAD 는 `./gradlew javadoc` 이 error 1 로 **실패**하던 상태였다(`GeminiProperties` Retry Javadoc 본문 줄머리의 `@DefaultValue` → unknown block tag). 이제 error 0 으로 통과한다. raw `<script>`·`<사전ID>`·`<headless-private-ip>` 등은 전부 `{@code ...}` 로 감쌌다.

### 코드 불변 게이트 (이 작업의 안전장치)

첫 시도에서 주석 전환 중 `HttpHeadlessRenderer` 의 필드 선언 한 줄이 함께 삭제돼 빌드가 깨진 사고가 있었다. 그래서 이번엔 **주석을 제거한 소스를 전후 비교**하는 게이트를 걸었다(문자열·텍스트 블록을 존중해 주석만 제거 후 공백 정규화하여 토큰 비교). 전환 후 105개 파일 전부 "주석 외 코드 변경 없음" PASS, `./gradlew test`·`javadoc` 통과.

**주석 일괄 전환 작업은 앞으로도 이 3중 게이트로 마감한다: ① 주석 제거 후 소스 동일성 ② test ③ javadoc error 0.**

## 4. 주석 전환 중 발견한 코드 이상 (고치지 않음 — 별도 작업)

주석을 쓰려면 코드를 정독해야 하므로 부수적으로 드러난 것들이다. 심각도 순.

1. ~~**헤드리스 경로의 redirect SSRF 비대칭**~~ — **부분 해소(#20)**. `final_url` 도 `InternalHostGuard` 를 태워 내부 주소가 최종 귀결점이면 렌더 전체를 거부한다. 다만 이 검증은 **사후**라 내부 주소로의 요청 자체는 못 막고, "외부 → 내부 → 외부" 체인도 못 잡는다(CodeRabbit 지적, 타당). **hop 단위 차단은 renderer repo 몫으로 남았다** — 실측 확인 결과 renderer 에는 SSRF·egress 가드가 전혀 없고(inbound SG 경계만) 브라우저가 redirect 를 자유롭게 따라간다. renderer 의 navigation 계층에서 자동 redirect 를 끄고 Location 마다 판정하거나, 렌더 박스에 egress 정책을 세워야 완결된다.
2. **`ProductImage.of(null, ...)` → 500** — null 검사 전에 `bytes.length` 를 읽어 NPE 가 된다. 계약상 422(IMAGE_UNSUPPORTED)여야 할 입력이 일시 실패로 오분류된다.
3. ~~**이미지 경로의 정규화 비대칭**~~ — **해소(#20)**. — `GeminiImageResult` 가 `ProductSnapshot.fromExtracted` 를 우회해, LLM 이 준 음수 가격·blank name 이 link 경로와 달리 검증 없이 통과한다(주석으로만 경고돼 있었다).
4. **`ProductImage.mimeTypeOfExtension` 하드코딩 switch** — `MIME_TO_EXTENSION` Javadoc 이 약속한 "포맷 추가는 한 곳"이 실제로는 깨져 있다(포맷 추가 시 두 곳 수정). 앞선 리뷰에서 파생 역맵으로 고쳤으나 그 워킹트리가 폐기돼 미반영.
5. **`ImageCropper.crop` 의 첫 `catch (IOException)` 이 로그 없이 null 반환** — 디코딩 IO 실패가 관측되지 않는다(두 번째 catch 만 `log.warn`).
6. **기본값 이중 정본** — `FetchProperties.defaults()`·`HeadlessExtractionProperties.of()` 가 `@DefaultValue` 수치를 코드로 다시 박는다. 한쪽만 바뀌면 바인딩 경로와 테스트 경로 기본값이 조용히 갈린다.
7. 잔가지: `ProductImage.EXTENSIONS` 가 repo 전체에서 미사용(죽은 public 상수 가능성) · `GeminiApiException.clientError` 와 `parseError` 가 code·permanent·cause 모두 동일해 관측상 구분 불가 · `ProductLink.HTTP_SCHEMES` 는 원소가 `https` 하나인데 이름이 복수형 · `parsePrice` 가 가격 범위 표기("10,000-20,000")를 INVALID_VALUE 로 떨구는 것이 의도인지 미확인.
8. 테스트 잔가지: `ProductLinkTest` 가 주석으로 주장했던 `safeLogString` 커버리지가 실제로는 없음(주석은 정정) · `ProductLinkExtractE2ETest` 가 preview 모델을 하드코딩해 "기본값은 GA 만" 정책과 충돌 · `HttpHeadlessRendererTest` 의 `assertEquals(null, ...)` → `assertNull` · `GeminiImageResultTest` 의 `@DisplayName` 이 없는 메서드명(`toProductSnapshot`)을 가리킴 · `ExtractionLinkIntegrationTest` 만 `Assertions` 대신 수동 `throw new AssertionError`.
