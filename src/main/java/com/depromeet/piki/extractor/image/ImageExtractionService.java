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

    public ProductSnapshot extract(String bucket, String key) {
        StoredImage stored = imageStorage.download(bucket, key);
        // download 가 S3 content-type 메타를 못 주면(메타 유실 등) 등록 때 key 에 박은 확장자로 mimeType 을 복원한다 —
        // 멀쩡한 raw 가 메타 결함만으로 비복구 실패하는 것을 막는다(key 확장자가 우리가 박은 신뢰값, content-type 은 fallback).
        String mimeType = mimeTypeFromKeyOrStored(key, stored);
        ProductImage image = ProductImage.of(stored.bytes(), mimeType);

        ImageExtraction extraction = productImageExtractor.extract(image);

        // 크롭이 불가능해도 원본을 올린다 — 호출자의 READY 불변식이 imageUrl 을 요구한다.
        byte[] resultBytes = croppedOrOriginal(image, extraction);
        String imageUrl = imageStorage.upload(bucket, resultBytes, "items/" + UUID.randomUUID() + ".png", "image/png");
        log.info("image extract bucket={} key={} croppedUrl={}", bucket, key, imageUrl);

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

    private byte[] croppedOrOriginal(ProductImage image, ImageExtraction extraction) {
        if (extraction.boundingBox() == null) {
            return image.bytes();
        }
        byte[] cropped = imageCropper.crop(image.bytes(), extraction.boundingBox());
        return cropped != null ? cropped : image.bytes();
    }
}
