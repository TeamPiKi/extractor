package com.depromeet.piki.extractor.support;

import com.depromeet.piki.extractor.common.storage.ImageStorage;
import com.depromeet.piki.extractor.common.storage.StoredImage;
import java.util.function.BiFunction;

/**
 * 외부 S3 경계를 통합 테스트에서 격리하는 stub. download 는 람다로 시나리오별 교체(default throw — 명시 세팅
 * 강제), upload 는 고정 URL 을 돌려준다 — 테스트가 보는 것은 URL 값 자체가 아니라 "업로드가 일어났고 그 URL 이
 * 응답 imageUrl 로 흐르는지"다.
 */
public class StubImageStorage implements ImageStorage {

    public static final String UPLOADED_URL =
        "https://stub-piki-images.s3.ap-northeast-2.amazonaws.com/items/stub.png";

    public BiFunction<String, String, StoredImage> onDownload = (bucket, key) -> {
        throw new IllegalStateException("stub.onDownload 를 테스트 본문에서 명시 세팅해야 한다. CLAUDE.md '테스트' 절 참고.");
    };

    /**
     * bbox 크롭 배선(크롭 결과가 실제로 업로드로 이어지는지)을 API 레벨에서 단언하는 데 쓴다. 공유 컨텍스트라
     * 값이 테스트 간 남을 수 있으므로, 검증하는 테스트가 본문에서 먼저 null 로 초기화한다.
     */
    public byte[] lastUploadedBytes;

    /**
     * 위장 업로드 회귀(#35)를 잡으려면 바이트만으로는 부족하다 — 크롭 불가 포맷(HEIC 등)의 원본을 {@code .png} ·
     * {@code image/png} 로 올리던 버그는 key·content-type 을 봐야 드러난다. 같은 이유로 테스트 본문이 먼저 초기화한다.
     */
    public String lastUploadedKey;

    public String lastUploadedContentType;

    @Override
    public StoredImage download(String bucket, String key) {
        return onDownload.apply(bucket, key);
    }

    @Override
    public String upload(String bucket, byte[] bytes, String key, String contentType) {
        lastUploadedBytes = bytes;
        lastUploadedKey = key;
        lastUploadedContentType = contentType;
        return UPLOADED_URL;
    }
}
