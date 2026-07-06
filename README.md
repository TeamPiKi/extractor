# PIKI-Extractor

PIKI 의 상품 추출 서비스. 상품 URL(또는 S3 이미지)을 받아 fetch → 구조화 파싱(JSON-LD/OpenGraph) → LLM(Gemini) fallback → 정규화를 거쳐 추출 결과를 반환한다. PIKI-Server 의 파싱 파이프라인을 분리한 것으로, 소비자는 PIKI-Server 의 outbox 워커 하나뿐인 내부 서비스다.

- **API 계약**: [docs/api-contract.md](docs/api-contract.md) — 응답 3갈래(2xx / 422 / 그 외=재시도), additive-only 진화
- **스택**: Java 25 · Spring Boot 4 · virtual threads · 무상태(DB 없음)
- **관련 repo**: [PIKI-Server](https://github.com/depromeet/PIKI-Server) (호출자), PIKI-HeadlessBrowser (차단 우회 렌더링, private)

## 실행

```bash
./gradlew test      # Docker 불필요 (DB 없음)
./gradlew bootRun   # 기본 포트 8090
```
