package com.depromeet.piki.extractor.probe;

import com.depromeet.piki.extractor.common.exception.ExtractionErrorCode;
import com.depromeet.piki.extractor.common.exception.ExtractionException;

/**
 * 모델 프로브의 확정 거절. 둘 다 permanent 라 계약상 422 로 나가고, 호출자(core 백오피스)는 code 로 사유를
 * 갈라 화면 문구를 고른다.
 *
 * <p>일시 실패는 여기서 만들지 않는다 — 프로브가 실패한 이유가 모델이 아니라 외부 사정(5xx·429·타임아웃)이면
 * 원래 예외를 그대로 전파해 502 로 내보낸다. 그래야 호출자가 "이 모델은 안 된다"와 "지금은 확인할 수 없다"를
 * 구분해 안내하고, 멀쩡한 모델이 일시 장애 때문에 지워지지 않는다.
 */
public final class ModelProbeException extends ExtractionException {

    private static final String USER_MESSAGE = "모델을 사용할 수 없습니다.";

    private ModelProbeException(ExtractionErrorCode code) {
        super(USER_MESSAGE, code, true, null);
    }

    public static ModelProbeException notFound() {
        return new ModelProbeException(ExtractionErrorCode.MODEL_NOT_FOUND);
    }

    public static ModelProbeException incompatible() {
        return new ModelProbeException(ExtractionErrorCode.MODEL_INCOMPATIBLE);
    }
}
