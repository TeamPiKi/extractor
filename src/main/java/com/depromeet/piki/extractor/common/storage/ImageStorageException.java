package com.depromeet.piki.extractor.common.storage;

import com.depromeet.piki.extractor.common.exception.ExtractionErrorCode;
import com.depromeet.piki.extractor.common.exception.ExtractionException;

/**
 * 스토리지(S3) 실패의 계약 예외 — 호출자(PIKI-Server 워커)는 일시 실패로 받아 PROCESSING 유지 후 recover 재시도한다.
 * <p>message 는 로그·디버깅용 고정 문구이고 응답 body 에는 code 만 나간다(내부 정보 비노출).
 */
public final class ImageStorageException extends ExtractionException {

    private ImageStorageException(String message, ExtractionErrorCode code, Throwable cause) {
        // 스토리지는 우리 밖 의존성이라 정상 요청도 닿을 수 있는 계약 응답이다 — 그래서 불변식 위반이 아니라 일시 실패다.
        super(message, code, false, cause);
    }

    public static ImageStorageException uploadFailed(Throwable cause) {
        return new ImageStorageException(
            "이미지를 저장하지 못했어요. 잠시 후 다시 시도해 주세요.", ExtractionErrorCode.STORAGE_ERROR, cause);
    }

    public static ImageStorageException downloadFailed(Throwable cause) {
        return new ImageStorageException(
            "이미지를 불러오지 못했어요. 잠시 후 다시 시도해 주세요.", ExtractionErrorCode.STORAGE_ERROR, cause);
    }
}
