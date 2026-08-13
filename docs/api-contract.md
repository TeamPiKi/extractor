# extractor API 계약 (이전됨)

계약 정본은 이 repo 를 떠나 **[TeamPiKi/infra](https://github.com/TeamPiKi/infra)** 로 옮겼다.

| 무엇 | 어디 |
|---|---|
| 계약 본문 (엔드포인트·응답 3갈래·타임아웃 예산·진화 규칙) | `contracts/extraction-api.md` |
| 실패 code 카탈로그 (기계 판정용) | `contracts/extraction-error-codes.yaml` |

**왜 옮겼나**: 계약은 extractor 와 소비자(core) 양쪽이 지키는 약속인데 정본이 한쪽 repo 안에 있으면 다른 쪽은 사본을 들게 되고, 사본은 조용히 어긋난다(계약 문서가 명시한 code 의 core 동등물이 실제로는 구현되지 않은 채 양쪽 CI 가 초록불이던 사례). 정본을 공용 repo 로 올리고, code 목록처럼 기계로 가를 수 있는 부분은 카탈로그로 떼어 각 repo 의 CI 가 대조한다 — 이 repo 에서는 `ExtractionErrorCodeCatalogTest` 가 `ExtractionErrorCode` 와 카탈로그의 일치를 강제한다.

카탈로그는 로컬에서 infra 의 `install.sh` 가, CI 에서는 `ci.yml` 의 checkout 스텝이 `shared-infra/contracts/` 에 놓는다.
