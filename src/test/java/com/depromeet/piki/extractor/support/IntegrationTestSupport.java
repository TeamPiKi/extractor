package com.depromeet.piki.extractor.support;

import org.springframework.boot.test.context.SpringBootTest;

// 모든 통합 테스트가 공유하는 단일 컨텍스트(캐시 보존). @SpringBootTest 는 이 한 곳에만 선언한다.
// 외부 경계 stub(GeminiClient 등)은 IntegrationStubs 한 곳에 등록하고 여기서 @Import 한다 — 파싱 포팅(3단계)과 함께 도입.
@SpringBootTest
public abstract class IntegrationTestSupport {
}
