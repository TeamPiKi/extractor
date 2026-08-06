package com.depromeet.piki.extractor.probe;

import com.depromeet.piki.extractor.common.exception.ExtractionException;
import com.depromeet.piki.extractor.extraction.gemini.GeminiApiException;
import com.depromeet.piki.extractor.extraction.gemini.GeminiClient;
import com.depromeet.piki.extractor.extraction.gemini.GeminiExtractionRequest;
import com.depromeet.piki.extractor.extraction.gemini.GeminiExtractionResult;
import com.depromeet.piki.extractor.image.gemini.GeminiImageRequest;
import com.depromeet.piki.extractor.image.gemini.GeminiImageResult;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 모델 유효성 프로브. 호출자(core 백오피스)가 모델을 저장하기 전에 "이 모델이 이 경로에서 실제로 동작하는가"를
 * 묻는 자리다.
 *
 * <p>판정을 메타 조회가 아니라 실제 generateContent 로 한다. 메타 조회는 모델의 존재만 보지만, 우리 요청은
 * 응답 스키마와 thinking 설정을 함께 싣고 그 비호환은 400 이다. 400 은 추출 경로에서 대체 대상이 아니라 곧
 * 파싱 전건 실패이므로, 존재만 확인하는 게이트는 정작 막아야 할 실패를 못 막는다.
 *
 * <p>그래서 각 경로가 실제로 쓰는 요청을 그대로 태운다 — 최소 입력이지만 wire 모양은 운영과 같다.
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class ModelProbeService {

    /** 실제로 fetch 하지 않는 자리표시 URL. .invalid 는 RFC 2606 이 예약한 TLD 라 실존 호스트와 겹치지 않는다. */
    private static final URI PROBE_URL = URI.create("https://probe.invalid/product");

    /** 토큰을 최소로 쓰되 추출기가 보내는 것과 같은 모양이도록, 상품 페이지의 뼈대만 남긴 HTML. */
    private static final String PROBE_HTML = "<html><body><h1>probe</h1></body></html>";

    /** 1x1 PNG. 이미지 경로의 wire(inlineData + 대문자 enum 스키마 + thinkingConfig)를 태우기 위한 최소 픽셀이다. */
    private static final String PROBE_IMAGE_BASE64 =
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==";

    private static final String PROBE_IMAGE_MIME_TYPE = "image/png";

    private final GeminiClient geminiClient;

    /** 유효하면 그냥 반환하고, 아니면 계약 예외로 거절 사유를 알린다. */
    public void probe(String model, ProbeTarget target) {
        try {
            callOnce(model, target);
            log.info("model probe ok target={} model={}", target, model);
        } catch (GeminiApiException e) {
            throw translate(e, model, target);
        }
    }

    /**
     * 대체 없는 경로로 부른다 — 대체가 일어나면 없는 모델을 넣어도 기본 모델이 성공해 프로브가 통과한다.
     *
     * <p>응답 파싱까지 요구하는 것도 의도다. 모델이 200 을 주면서 우리 스키마를 안 맞추면 실제 추출에서도
     * 못 쓰므로, 그 경우까지 거절로 떨어져야 게이트가 제 구실을 한다.
     */
    private void callOnce(String model, ProbeTarget target) {
        switch (target) {
            case LINK -> geminiClient.generateContentExactly(
                GeminiExtractionRequest.forHtmlExtraction(PROBE_URL, PROBE_HTML),
                GeminiExtractionResult.class,
                model
            );
            case IMAGE -> geminiClient.generateContentExactly(
                GeminiImageRequest.forImageAnalysis(PROBE_IMAGE_BASE64, PROBE_IMAGE_MIME_TYPE),
                GeminiImageResult.class,
                model
            );
        }
    }

    /**
     * 404 만 "그런 모델이 없다"로 가르고, 나머지 확정 실패는 전부 "이 경로에서 못 쓴다"로 묶는다 — 요청 스키마
     * 비호환(400)이든 200 인데 스키마를 못 맞춘 응답이든, 운영자가 할 일은 같은 하나(다른 모델을 고른다)라
     * 사유를 더 쪼개도 화면에서 쓸 데가 없다.
     *
     * <p>일시 실패는 그대로 전파한다. 모델 탓이 아닌 실패(5xx·429·타임아웃)를 거절로 바꾸면, 외부가 잠깐
     * 흔들린 사이에 멀쩡한 모델이 "쓸 수 없는 모델"로 판정된다.
     */
    private ExtractionException translate(GeminiApiException e, String model, ProbeTarget target) {
        if (!e.permanent()) {
            log.warn("model probe transient target={} model={}", target, model);
            return e;
        }
        Integer status = e.httpStatus();
        if (status != null && status == HttpStatus.NOT_FOUND.value()) {
            log.info("model probe rejected reason=not_found target={} model={}", target, model);
            return ModelProbeException.notFound();
        }
        log.info("model probe rejected reason=incompatible target={} model={} status={}", target, model, status);
        return ModelProbeException.incompatible();
    }
}
