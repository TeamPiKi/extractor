# extractor API 계약

이 문서가 계약의 single source 다. 구현(springdoc `/v3/api-docs`)과 어긋나면 이 문서를 기준으로 구현을 고친다.

소비자는 core 의 outbox 워커 하나뿐이다. 공개 API 가 아니며, 보안그룹으로 내부망에서만 접근한다(별도 인증 없음).

## 0. 설계 불변식

- Extractor 는 **무상태**다. DB 없음, 호출 간 상태 없음. 같은 요청이 중복 도착해도 상태 오염이 없다(중복의 대가는 LLM 비용 한 번뿐). Extractor 에 상태를 넣고 싶어지면 설계 경고 신호다.
- 재시도·내구성·상태 전이는 전부 호출자(core 의 item_snapshots outbox)의 책임이다. Extractor 는 "단건 시도 1회"에만 답한다.
- 에스컬레이션(plain fetch → headless 브라우저)은 Extractor 내부 관심사다. 응답 계약에 드러나지 않는다 — 호출자는 어떤 fetch 전략이 쓰였는지 모른다. 단 "어느 플랫폼을 처음부터 헤드리스로 보낼지"의 **정책**은 호출자(DB·백오피스)가 주인이라, 요청 필드 `headlessFirst` 로 힌트만 받는다(§2) — 무상태 불변식을 지키는 선에서의 유일한 정책 수용 지점이다.

## 1. 응답 3갈래 (전이 규약)

| Extractor 응답 | 의미 | core 전이 |
|---|---|---|
| 2xx + 추출 결과 | 성공 | `markReady` |
| 422 + `{code}` | 확정 실패 (재시도 무의미) | 즉시 `markFailed` |
| 그 외 전부 (5xx·타임아웃·연결 실패·미지의 상태) | 일시 실패 | PROCESSING 유지 → recover 재시도 (attempt 상한 2) |

- **전이 판정은 HTTP status 만 사용한다.** 422 body 의 `code` 는 관측·디버깅용이며, 호출자가 모르는 code 여도 422 면 확정 실패로 처리한다(tolerant reader).
- **fail-safe 원칙**: 분류할 수 없는 실패는 전부 "일시"로 떨어진다. 확정 실패 신호는 422 하나뿐이다. 호출자의 attempt 상한이 재시도 비용을 바운드한다.

## 2. 엔드포인트

### POST `/internal/extractions/link` — URL 상품 추출 (이관 1차)

요청:

```json
{ "url": "https://www.musinsa.com/products/12345", "headlessFirst": false }
```

- `url` (필수): https 스킴의 상품 페이지 URL. 형식·스킴·미지원 플랫폼의 동기 검증은 호출자(core 등록 경계)가 이미 끝냈다는 전제이나, Extractor 도 자기 경계에서 방어 검증한다(다층 방어).
- `headlessFirst` (선택, 기본 false — additive, 이관 7단계): 호출자의 플랫폼 라우팅 정책(`HEADLESS_FIRST`, DB·백오피스 동적 설정) 힌트. true 면 plain(정적 fetch)을 건너뛰고 처음부터 헤드리스 브라우저로 추출한다. 정책의 단일 진실은 호출자 DB 에 있고 무상태인 Extractor 는 요청 단위로만 받는다. Extractor 의 `product.extract.headless.enabled` 가 꺼져 있으면 무시된다(스위치가 힌트보다 우선).
- 헤더 `X-Correlation-Id` (선택): 호출자의 item_snapshot id. 로그·trace 상관용이며 동작에 영향 없다.

성공 200:

```json
{
  "name": "나이키 에어포스",
  "imageUrl": "https://...",
  "currentPrice": 99000,
  "currency": "KRW",
  "finalUrl": "https://www.musinsa.com/products/6760200",
  "method": "STRUCTURED"
}
```

- `finalUrl` (additive, core#825): 리다이렉트를 따라간 최종 페이지 URL. 호출자가 상품 정체성(canonical) 정규화의 입력으로 쓴다 — 단축링크(onelink 등)는 경로가 불투명 코드라 이 값 없이 같은 상품을 알아볼 수 없다. link 경로는 항상 채워지고 image 경로는 null. 호출자는 이 값이 없으면(구버전 Extractor) canonical 확정을 건너뛴다 — 배포 순서 무관.
- `method` (additive, core#825): 값을 만든 추출 경로. `STRUCTURED`(구조화 파싱, 결정론적) | `LLM`(Gemini — URL fallback·image 경로). 호출자가 snapshot 출처(SERVER/SERVER_LLM)를 구분 저장하는 근거다. tolerant reader 라 모르는 값이 와도 무시하고 출처 미기록으로 둔다.
- **`name`(non-blank)·`imageUrl`·`currentPrice` 의 non-null 을 Extractor 가 보장한다** — core 의 READY 불변식(`requireReadyInvariant`: name·price·imageUrl·extractedAt, extractedAt 은 호출자가 전이 시점에 채움)과 동일 조건이다. 보장할 수 없으면 성공이 아니라 422(`UNTRUSTWORTHY_VALUE`)다. `currency` 는 READY 필수가 아니라 **nullable** 이다. 호출자의 엔티티 불변식은 최후 보루로 유지된다.

확정 실패 422:

```json
{ "code": "NOT_PRODUCT_PAGE" }
```

| code | core 기준 동등물 |
|---|---|
| `NOT_PRODUCT_PAGE` | `ProductSnapshotException.notProductPage` — 상품 페이지가 아님 |
| `UNTRUSTWORTHY_VALUE` | `ProductSnapshotException.untrustworthyValue` — 추출값이 범위·상식 위반 |
| `FETCH_CLIENT_ERROR` | `PageFetchException.clientError` — 대상 4xx (403 차단·404·429 등) |
| `BLOCKED_HOST` | `PageFetchException.blockedHost` — SSRF 차단 (headless 에스컬레이션 절대 금지 대상) |
| `TOO_MANY_REDIRECTS` | `PageFetchException.tooManyRedirects` |
| `MALFORMED_REDIRECT` | `PageFetchException.malformedRedirect` |
| `PERMANENT_UPSTREAM` | `PageFetchException.permanentUpstreamError` — 대상 500/501 (봇 차단 추정) |
| `LLM_INVALID_RESPONSE` | `GeminiApiException` clientError/parseError/noTextPart — 재시도 무의미한 LLM 실패 |
| `INVALID_URL` | url 형식·스킴 위반. 정상 흐름에선 호출자가 동기 검증해 도달하지 않는다(방어) |

일시 실패 (Extractor 는 502 를 쓴다, 호출자는 status 구분 없이 "2xx/422 외 전부"로 처리):

- 대상 몰 502/503/504·연결 실패·빈 body (`UPSTREAM_ERROR`)
- Gemini 5xx/429/408/transport 오류 (`LLM_UPSTREAM`)
- 헤드리스 렌더 차단 (`HEADLESS_BLOCKED`, 이관 7단계 추가) — 렌더 서비스의 BLOCK 판정(HTTP 401/403/405/429/490 + 챌린지 title)은 429·일시 챌린지가 섞여 영구/일시를 못 가르므로 fail-safe 로 일시. 결정론적 차단의 재시도 낭비는 attempt 상한이 바운드
- 헤드리스 렌더 서비스 연결 실패·타임아웃·빈 렌더·브라우저 오류 (`HEADLESS_UPSTREAM`, 이관 7단계 추가). 렌더는 됐는데 렌더 서비스 자체 파서가 title·price 를 못 찾은 경우(verdict=EMPTY/PARTIAL)는 실패가 아니다 — HTML 이 있으면 Extractor 파이프라인(구조화·LLM)이 이어서 추출한다
- body 에 code 를 실을 수 있으나 호출자는 읽지 않는다(관측용).

### POST `/internal/extractions/image` — S3 이미지 OCR 추출 + 크롭 (이관 6단계, 계약만 선확정)

요청:

```json
{ "bucket": "dev-piki-images-250758375457", "key": "items/raw/0f3a....png" }
```

- **`bucket` 을 요청이 준다** — Extractor 는 dev/staging/prod 세 환경 트래픽을 받고 각 환경의 이미지 버킷이 다르다(dev-piki-images-* · staging-piki-images-* · piki-images-*). 버킷을 고정 config 로 두지 않고 요청별로 받아 버킷 무관하게 동작한다. IAM 은 `*piki-images-250758375457/items/*` 와일드카드로 세 버킷을 덮는다.
- `key`: raw 원본 object key(등록 시 본 서버가 `items/raw/{uuid}.{ext}` 로 durable 적재한 것).

성공 200 (link 경로와 **동일한 필드 모양**):

```json
{ "name": "...", "imageUrl": "https://dev-piki-images-250758375457.s3.ap-northeast-2.amazonaws.com/items/0f3a....png", "currentPrice": 12000, "currency": "KRW" }
```

- Extractor 가 `download(bucket,key) → OCR 추출 → bbox 크롭(불가 시 원본) → upload(bucket, items/{uuid}.png)` 를 다 하고, 업로드한 결과 이미지의 public URL 을 `imageUrl` 로 돌려준다. imageUrl 은 항상 non-null(크롭 실패해도 원본을 올린다 — 본 서버 워커의 `bbox?.crop ?: 원본` 동작과 동일).
- non-null 규약은 link 와 같다: name(non-blank)·imageUrl·currentPrice 를 Extractor 가 보장, 못 채우면 422(`UNTRUSTWORTHY_VALUE`).

422 code 추가분: `IMAGE_UNSUPPORTED`(빈 이미지·미지원 MIME). 이미지에서 상품 식별 실패는 link 와 같은 `UNTRUSTWORTHY_VALUE` 를 재사용한다.
일시 실패 추가분: `STORAGE_ERROR` (S3 read/write 실패).

## 3. 타임아웃 예산

| 층 | 값 | 근거 |
|---|---|---|
| core stale 판정 | 60s | `ItemParsingScheduler.STALE_TIMEOUT` (기존 값) |
| core → Extractor HTTP read | 55s (connect 2s) | stale 미만 — recover 의 유령 중복 발주 방지. link·image 공용 |
| Extractor 내부 합계 (link) | 약 50s | 아래 합 + 여유 |
| 대상 몰 fetch (link) | connect 5s / read 15s | 현행 유지 |
| 헤드리스 render (link, 7단계) | connect 2s / read 20s | 실측 전형 1.6~5.5s(kream 프록시 포함) 대비 약 4배 여유. headless-first 최악(connect 2 + render 20 + LLM 30 = 약 52s)이 호출자 read 55s 안에 들도록 상한 |
| Gemini | read 30s | 현행 유지 (link LLM fallback·image OCR 동일) |
| Extractor 내부 합계 (image) | 약 40s | S3 download + Gemini OCR 30s + crop + 결과 upload. S3 는 동일 리전이라 수 초 |

**안쪽 예산은 항상 바깥보다 작아야 한다.** link 와 image 가 호출자 read 55s 를 공유하므로, 어느 경로든 Extractor 내부 값을 늘릴 땐 이 표를 갱신하고 core 쪽 read 타임아웃과 함께 재검증한다.

예외적으로 **에스컬레이션 경로(plain 실패 → headless)의 최악 스택**은 호출자 read 55s 를 넘을 수 있다. plain fetch 는 수동 redirect 추적(hop 상한 3 = 요청 최대 4회)마다 connect/read 타임아웃이 **새로 적용**되므로 fetch 단독의 이론 최악이 이미 약 88s 다(이 특성은 headless 이전부터 존재). 여기에 render 22s + LLM 30s 가 얹히면 이론 최악 약 140s — 단, 각 단이 전부 타임아웃까지 끄는 경우는 실측상 없다시피 하고(차단은 대개 즉시 4xx/5xx 로 떨어져 fetch 가 빨리 실패한다), 넘치면 호출자는 read 타임아웃 → 일시 실패로 처리해 recover 가 재시도한다. 그 사이 Extractor 가 계속 돌아 중복 발주가 겹쳐도 Extractor 는 무상태라 안전하고(§0 — 중복의 대가는 LLM 비용 한 번), attempt 상한 2 가 총비용을 바운드한다. 이 스택을 55s 안에 구겨 넣으려면 render 예산이 실측 대비 무의미하게 얇아져(5s 이하) recall 을 잃는다 — 의도된 트레이드오프다.

## 4. 진화 규칙

- **additive-only**: 응답 필드 추가·422 code 추가는 자유. 필드 제거·의미 변경·타입 변경은 금지 — 필요하면 새 경로로 분리한다.
- **배포 순서: Extractor 먼저, 소비자(core) 나중.** 본 서버 staging 이 extractor-prod 를 기본으로 바라보는 구성이 이 순서 위반을 릴리스 전에 잡는다.
- 호출자는 tolerant reader — 모르는 응답 필드·code 를 무시한다.

## 5. 관측

- W3C `traceparent` 헤더를 수용해 core 의 `item.parse` span 아래로 연결된다(micrometer tracing 기본 동작).
- 메트릭 `product.extract{via,reason}`·`product.extract.escalation{outcome,category}` 은 core 에서 이 서비스로 이동한다. `application=piki-extractor` 라벨로 본 서버 시계열과 구분된다.
