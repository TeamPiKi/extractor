package com.depromeet.piki.extractor.image;

import com.depromeet.piki.extractor.common.storage.ImageStorage;
import com.depromeet.piki.extractor.common.storage.StoredImage;
import com.depromeet.piki.extractor.domain.ExtractionMethod;
import com.depromeet.piki.extractor.domain.ProductSnapshot;
import com.depromeet.piki.extractor.image.domain.ProductImage;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 이미지 추출 오케스트레이터. 상태 전이(markReady·raw 회수)는 호출자(core) 소관이고,
 * 이 서비스는 download→extract→crop→upload 만 책임진다.
 *
 * <p>bucket 을 요청이 주므로 환경별 버킷을 모두 다룬다.
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class ImageExtractionService {

    private final ProductImageExtractor productImageExtractor;
    private final ImageCropper imageCropper;
    private final ImageStorage imageStorage;

    /** @param model 호출자가 지정한 LLM 모델(없으면 null). 해석·대체는 Gemini 클라이언트가 지므로 그대로 넘긴다. */
    public ProductSnapshot extract(String bucket, String key, String model) {
        StoredImage stored = imageStorage.download(bucket, key);
        // download 가 S3 content-type 메타를 못 주면(메타 유실 등) 등록 때 key 에 박은 확장자로 mimeType 을 복원한다 —
        // 멀쩡한 raw 가 메타 결함만으로 비복구 실패하는 것을 막는다(key 확장자가 우리가 박은 신뢰값, content-type 은 fallback).
        String mimeType = mimeTypeFromKeyOrStored(key, stored);
        ProductImage image = ProductImage.of(stored.bytes(), mimeType);

        ImageExtraction extraction = productImageExtractor.extract(image, model);

        // 크롭이 불가능해도 원본을 올린다 — 호출자의 READY 불변식이 imageUrl 을 요구한다.
        UploadTarget target = croppedOrOriginal(image, extraction);
        String objectKey = "items/" + UUID.randomUUID() + "." + target.extension();
        String imageUrl = imageStorage.upload(bucket, target.bytes(), objectKey, target.mimeType());
        // cropped 를 함께 남긴다 — croppedUrl 이라는 이름과 달리 크롭을 건너뛴 경우가 섞여 있어, 이 값 없이는
        // "원본이 그대로 올라간 비율"을 사후에 알 수 없다.
        log.info(
            "image extract bucket={} key={} croppedUrl={} cropped={}",
            bucket, key, imageUrl, target.cropped());

        // 이미지 경로는 원본 URL 이 없어 finalUrl 도 없고, 추출이 Gemini 라 method 는 항상 LLM 이다.
        ProductSnapshot s = extraction.snapshot();
        return new ProductSnapshot(s.link(), s.name(), imageUrl, s.currentPrice(), s.currency())
            .withOrigin(null, ExtractionMethod.LLM);
    }

    private String mimeTypeFromKeyOrStored(String key, StoredImage stored) {
        String extension = key.substring(key.lastIndexOf('.') + 1);
        String fromKey = ProductImage.mimeTypeOfExtension(extension);
        return fromKey != null ? fromKey : stored.contentType();
    }

    /**
     * 업로드할 결과물. 크롭이 실제로 일어났으면 PNG 인코딩 결과이고, 크롭 불가 포맷(HEIC·WebP·HEIF 는 ImageIO 에
     * 디코더가 없다)이면 원본 바이트 그대로다.
     *
     * <p>확장자·content-type 을 결과물과 함께 나르는 이유: 예전에는 둘 다 png 로 하드코딩돼 있어, 크롭을 건너뛴
     * HEIC 바이트가 {@code .png} · {@code image/png} 로 위장돼 저장됐다. 브라우저 대부분이 그 파일을 렌더링하지
     * 못한다. 등록이 허용하는 5개 포맷 중 셋이 이 경로를 탄다(#35).
     */
    private record UploadTarget(byte[] bytes, String extension, String mimeType, boolean cropped) {}

    private UploadTarget croppedOrOriginal(ProductImage image, ImageExtraction extraction) {
        if (extraction.boundingBox() == null) {
            return original(image);
        }
        byte[] cropped = imageCropper.crop(image.bytes(), extraction.boundingBox());
        if (cropped == null) {
            return original(image);
        }
        return new UploadTarget(cropped, "png", "image/png", true);
    }

    private UploadTarget original(ProductImage image) {
        return new UploadTarget(image.bytes(), image.extension(), image.mimeType(), false);
    }
}
