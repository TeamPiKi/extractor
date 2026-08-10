# extractor

PiKi 의 상품 추출 서비스. 상품 URL(또는 S3 이미지)을 받아 fetch → 구조화 파싱(JSON-LD/OpenGraph) → LLM fallback → 정규화를 거쳐 추출 결과를 반환한다. 소비자는 core 뿐인 내부 서비스다.

전체 시스템 구성은 [core](https://github.com/TeamPiKi/core) 를 참고한다.

- **API 계약**: [docs/api-contract.md](docs/api-contract.md). 응답 3갈래(2xx / 422 / 그 외=재시도), additive-only 진화
- **스택**: Java · Spring Boot · virtual threads · 무상태(DB 없음)
