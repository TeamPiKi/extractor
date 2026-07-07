package com.depromeet.piki.extractor.image.domain;

import com.depromeet.piki.extractor.common.exception.ExtractionErrorCode;
import com.depromeet.piki.extractor.common.exception.ExtractionException;

// 이 서비스의 클라이언트는 PIKI-Server 워커이고,
// 빈 이미지·미지원 MIME 은 같은 바이트를 재시도해도 결과가 같으므로 확정 실패(permanent=true → 422)다.
// 셋 다 code=IMAGE_UNSUPPORTED (api-contract.md 의 "422 code 추가분: IMAGE_UNSUPPORTED").
// 세 팩토리의 서로 다른 message 는 로그·cause 용이며 응답 body 에는 code 만 나간다.
public final class ProductImageException extends ExtractionException {

    private ProductImageException(String message) {
        super(message, ExtractionErrorCode.IMAGE_UNSUPPORTED, true, null);
    }

    public static ProductImageException emptyImage() {
        return new ProductImageException("빈 이미지 파일은 올릴 수 없어요.");
    }

    public static ProductImageException unknownType() {
        return new ProductImageException("이미지 형식을 확인할 수 없어요.");
    }

    public static ProductImageException unsupportedType() {
        return new ProductImageException("지원하지 않는 이미지 형식이에요.");
    }
}
