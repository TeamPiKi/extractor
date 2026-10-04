# extractor API 계약 (이전됨)

계약 정본은 **[TeamPiKi/infra](https://github.com/TeamPiKi/infra)** 에 있다.

| 무엇 | 어디 |
|---|---|
| 동작 규칙 (응답 3갈래·타임아웃 예산·진화 규칙) | `contracts/extraction-api.md` |
| 요청·응답의 모양, 필드와 code 의 의미, code 분류 | `contracts/extraction.proto` |

proto 는 로컬에서 infra 의 `install.sh` 가, CI 에서는 `ci.yml`·`deploy.yml` 의 checkout 스텝이 `shared-infra/contracts/` 에 놓는다. `generateProto` 가 `com.depromeet.piki.contracts.extraction.v1` 패키지의 클래스를 만들고, `ExtractionErrorCodeCatalogTest` 가 도메인 code 와 계약의 일치를 강제한다. 와이어는 JSON 그대로다(`ProtobufJsonConverterConfig`).
