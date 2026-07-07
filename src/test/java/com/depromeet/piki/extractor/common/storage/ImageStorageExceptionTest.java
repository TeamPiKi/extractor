package com.depromeet.piki.extractor.common.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.depromeet.piki.extractor.common.exception.ExtractionErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// 스토리지 실패의 계약(permanent + code)을 고정한다 — permanent=false(일시 실패, 502), 전용 code 는 STORAGE_ERROR.
class ImageStorageExceptionTest {

    private final RuntimeException cause = new RuntimeException("boom");

    @Test
    @DisplayName("uploadFailed 는 일시 실패(permanent=false) + STORAGE_ERROR 로 매핑된다")
    void uploadFailedIsTransientStorageError() {
        ImageStorageException e = ImageStorageException.uploadFailed(cause);

        assertFalse(e.permanent());
        assertEquals(ExtractionErrorCode.STORAGE_ERROR, e.code());
        assertSame(cause, e.getCause());
    }

    @Test
    @DisplayName("downloadFailed 는 일시 실패(permanent=false) + STORAGE_ERROR 로 매핑된다")
    void downloadFailedIsTransientStorageError() {
        ImageStorageException e = ImageStorageException.downloadFailed(cause);

        assertFalse(e.permanent());
        assertEquals(ExtractionErrorCode.STORAGE_ERROR, e.code());
        assertSame(cause, e.getCause());
    }
}
