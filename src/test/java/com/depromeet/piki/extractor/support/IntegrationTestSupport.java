package com.depromeet.piki.extractor.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

// 모든 통합 테스트가 공유하는 단일 컨텍스트(캐시 보존). @SpringBootTest 는 이 한 곳에만 선언한다.
// 외부 경계 stub(PageFetcher·GeminiClient)은 IntegrationStubs 한 곳에 등록해 여기서 import 한다.
@SpringBootTest
@Import(IntegrationStubs.class)
public abstract class IntegrationTestSupport {
}
