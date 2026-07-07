package com.depromeet.piki.extractor.common.storage;

import com.depromeet.piki.extractor.common.exception.ExtractionErrorCode;
import com.depromeet.piki.extractor.common.exception.ExtractionException;

// 스토리지(S3) 실패의 계약 예외 — permanent=false(일시 실패, 502).
// 호출자(PIKI-Server 워커)가 PROCESSING 유지 후 recover 재시도한다.
// message 는 로그·디버깅용 고정 문구이고 응답 body 에는 code 만 나간다(내부 정보 비노출).
// code 는 스토리지 실패 전용 STORAGE_ERROR (docs/api-contract.md 의 code 표에 함께 있다).
public final class ImageStorageException extends ExtractionException {

    private ImageStorageException(String message, ExtractionErrorCode code, Throwable cause) {
        // 스토리지는 우리 밖 의존성이라 정상 요청도 닿을 수 있는 계약 응답이다 — permanent=false(일시 실패, 502).
        super(message, code, false, cause);
    }

    // 크롭 결과 업로드 실패.
    public static ImageStorageException uploadFailed(Throwable cause) {
        return new ImageStorageException(
            "이미지를 저장하지 못했어요. 잠시 후 다시 시도해 주세요.", ExtractionErrorCode.STORAGE_ERROR, cause);
    }

    // raw 원본 다운로드 실패(객체 없음·권한·네트워크) — 워커가 일시 오류로 받아 재시도한다.
    public static ImageStorageException downloadFailed(Throwable cause) {
        return new ImageStorageException(
            "이미지를 불러오지 못했어요. 잠시 후 다시 시도해 주세요.", ExtractionErrorCode.STORAGE_ERROR, cause);
    }
}
