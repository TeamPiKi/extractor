# extractor API 계약 (이전됨)

계약 정본은 **[TeamPiKi/infra](https://github.com/TeamPiKi/infra)** 에 있다.

| 무엇 | 어디 |
|---|---|
| 동작 규칙 (응답 3갈래·타임아웃 예산·진화 규칙) | `contracts/extraction-api.md` |
| 요청·응답의 모양, 필드와 code 의 의미, code 분류 | `contracts/extraction.proto` |
| renderer 응답의 모양 (내부 주소를 만났는지 포함) | `contracts/render.proto` |
| SSRF 검사의 내부 주소 목록 | `contracts/internal-ranges.txt` |

계약 파일은 로컬에서 infra 의 `install.sh` 가, CI 에서는 `ci.yml`·`deploy.yml` 의 checkout 스텝이 `shared-infra/contracts/` 에 놓는다. `generateProto` 가 `com.depromeet.piki.contracts.extraction.v1` 패키지의 클래스를 만들고, `ExtractionErrorCodeCatalogTest` 가 도메인 code 와 계약의 일치를 강제한다. 와이어는 JSON 그대로다(`ProtobufJsonConverterConfig`). 내부 주소 목록은 빌드가 classpath 의 `contracts/` 에 싣고, 파일이 빠진 빌드는 기동에서 실패한다.
