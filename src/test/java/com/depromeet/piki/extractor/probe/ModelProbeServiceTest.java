package com.depromeet.piki.extractor.probe;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.depromeet.piki.extractor.common.exception.ExtractionErrorCode;
import com.depromeet.piki.extractor.extraction.gemini.GeminiApiException;
import com.depromeet.piki.extractor.extraction.gemini.GeminiExtractionRequest;
import com.depromeet.piki.extractor.image.gemini.GeminiImageRequest;
import com.depromeet.piki.extractor.support.StubGeminiClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClientResponseException;

/**
 * 프로브 판정이 호출자(core 백오피스)의 저장 게이트와 맞물리는 지점을 고정한다. 이 판정이 곧 "이 모델을
 * 등록할 수 있는가"의 답이라, 사유를 어떻게 가르느냐가 화면 문구와 재시도 여부를 결정한다.
 */
class ModelProbeServiceTest {

    private static final String MODEL = "gemini-probe-target";

    private static GeminiApiException responseError(int status) {
        return GeminiApiException.fromResponseError(
            new RestClientResponseException("probe", status, String.valueOf(status), null, null, null)
        );
    }

    private ModelProbeService serviceThat(StubGeminiClient stub) {
        return new ModelProbeService(stub);
    }

    @Test
    @DisplayName("호출이 통과하면 프로브도 통과한다")
    void passes() {
        StubGeminiClient stub = new StubGeminiClient();
        stub.build = request -> null;

        assertDoesNotThrow(() -> serviceThat(stub).probe(MODEL, ProbeTarget.LINK));
        assertEquals(MODEL, stub.lastModel());
    }

    /**
     * 경로마다 요청 wire 가 다르다 — 링크는 responseJsonSchema, 이미지는 responseSchema 와 thinkingConfig 다.
     * 프로브가 그 경로의 실제 요청을 태우지 않으면, 한쪽만 되는 모델이 양쪽 다 되는 것으로 통과한다.
     */
    @Test
    @DisplayName("경로별로 그 경로의 실제 요청 모양을 태운다")
    void sendsPerTargetWire() {
        StubGeminiClient stub = new StubGeminiClient();
        stub.build = request -> null;

        serviceThat(stub).probe(MODEL, ProbeTarget.LINK);
        assertInstanceOf(GeminiExtractionRequest.class, stub.lastRequest());

        serviceThat(stub).probe(MODEL, ProbeTarget.IMAGE);
        assertInstanceOf(GeminiImageRequest.class, stub.lastRequest());
    }

    @Test
    @DisplayName("404 는 그런 모델이 없다는 확정 거절이다")
    void rejectsMissingModel() {
        StubGeminiClient stub = new StubGeminiClient();
        stub.build = request -> {
            throw responseError(404);
        };

        ModelProbeException e =
            assertThrows(ModelProbeException.class, () -> serviceThat(stub).probe(MODEL, ProbeTarget.LINK));
        assertEquals(ExtractionErrorCode.MODEL_NOT_FOUND, e.code());
        assertEquals(true, e.permanent());
    }

    @Test
    @DisplayName("400 은 이 경로에서 못 쓴다는 확정 거절이다")
    void rejectsIncompatibleModel() {
        StubGeminiClient stub = new StubGeminiClient();
        stub.build = request -> {
            throw responseError(400);
        };

        ModelProbeException e =
            assertThrows(ModelProbeException.class, () -> serviceThat(stub).probe(MODEL, ProbeTarget.IMAGE));
        assertEquals(ExtractionErrorCode.MODEL_INCOMPATIBLE, e.code());
    }

    /**
     * 200 인데 우리 스키마를 못 맞춘 응답도 거절이다 — 실제 추출에서도 못 쓸 모델이라, 통과시키면 게이트가
     * 정작 막아야 할 것을 놓친다.
     */
    @Test
    @DisplayName("응답 스키마를 못 맞추는 모델도 거절한다")
    void rejectsUnparseableResponse() {
        StubGeminiClient stub = new StubGeminiClient();
        stub.build = request -> {
            throw GeminiApiException.parseError(new IllegalStateException("schema mismatch"));
        };

        ModelProbeException e =
            assertThrows(ModelProbeException.class, () -> serviceThat(stub).probe(MODEL, ProbeTarget.LINK));
        assertEquals(ExtractionErrorCode.MODEL_INCOMPATIBLE, e.code());
    }

    /**
     * 모델 탓이 아닌 실패를 거절로 바꾸면, 외부가 잠깐 흔들린 사이에 멀쩡한 모델이 "쓸 수 없다"고 판정된다.
     * 원래 예외를 그대로 전파해 호출자가 502(일시)로 받고 재시도를 안내하게 한다.
     */
    @Test
    @DisplayName("5xx 는 거절이 아니라 일시 실패로 전파한다")
    void propagatesTransientFailure() {
        StubGeminiClient stub = new StubGeminiClient();
        stub.build = request -> {
            throw responseError(503);
        };

        GeminiApiException e =
            assertThrows(GeminiApiException.class, () -> serviceThat(stub).probe(MODEL, ProbeTarget.LINK));
        assertEquals(false, e.permanent());
    }
}
