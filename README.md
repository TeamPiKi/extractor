# extractor

PIKI 의 상품 추출 서비스. 상품 URL(또는 S3 이미지)을 받아 fetch → 구조화 파싱(JSON-LD/OpenGraph) → LLM(Gemini) fallback → 정규화를 거쳐 추출 결과를 반환한다. core 의 파싱 파이프라인을 분리한 것으로, 소비자는 core 의 outbox 워커 하나뿐인 내부 서비스다.

- **API 계약**: [docs/api-contract.md](docs/api-contract.md) — 응답 3갈래(2xx / 422 / 그 외=재시도), additive-only 진화
- **스택**: Java 25 · Spring Boot 4 · virtual threads · 무상태(DB 없음)
- **관련 repo**: [core](https://github.com/TeamPiKi/core) (호출자), renderer (차단 우회 렌더링, private)

## 실행

```bash
./gradlew test      # Docker 불필요 (DB 없음)
./gradlew bootRun   # 기본 포트 8090
```

## 운영 (prod 박스)

- 박스 자체: `infra/extractor/` (terraform, EC2·SG·IAM).
- 앱 배포·기동: GitHub Actions `Deploy` 워크플로(workflow_dispatch)가 기동의 SSOT — 이미지 push 후 공용 블록(run_container·healthcheck)으로 SSH 배포하며, 관측 opt-in 라벨·트레이스 env 도 여기서 붙는다.
- 관측(Alloy) 배선: `infra/scripts/provision-observability.sh` — 공통 Alloy 블록을 이 박스 값으로 호출(SSM 으로 박스에서 실행).
- 시크릿은 SSM Parameter Store(`/piki-extractor/*`)에서 읽는다.
- 관측 계약(라벨 opt-in 등)은 TeamPiKi/infra 의 `contracts/observability.md` 가 SSOT.
