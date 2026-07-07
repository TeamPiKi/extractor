package com.depromeet.piki.extractor.image;

import com.depromeet.piki.extractor.common.storage.ImageStorage;
import com.depromeet.piki.extractor.common.storage.StoredImage;
import com.depromeet.piki.extractor.domain.ProductSnapshot;
import com.depromeet.piki.extractor.image.domain.ProductImage;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

// 이미지 OCR 추출의 오케스트레이터 — S3 raw 원본을 읽고(download) OCR 추출·크롭 후 결과 이미지를 S3 에 올려(upload)
// 그 public URL 을 담은 ProductSnapshot 을 돌려준다. 상태 전이(markReady·raw 회수)는 호출자(PIKI-Server) 소관이고,
// 이 서비스는 download→extract→crop→upload 만 책임진다. bucket 은 요청이 주므로 세 환경 버킷 모두 다룬다.
@Component
public class ImageExtractionService {

    private static final Logger log = LoggerFactory.getLogger(ImageExtractionService.class);

    private final ProductImageExtractor productImageExtractor;
    private final ImageCropper imageCropper;
    private final ImageStorage imageStorage;

    public ImageExtractionService(
        ProductImageExtractor productImageExtractor,
        ImageCropper imageCropper,
        ImageStorage imageStorage
    ) {
        this.productImageExtractor = productImageExtractor;
        this.imageCropper = imageCropper;
        this.imageStorage = imageStorage;
    }

    public ProductSnapshot extract(String bucket, String key) {
        StoredImage stored = imageStorage.download(bucket, key);
        // download 가 S3 content-type 메타를 못 주면(메타 유실 등) 등록 때 key 에 박은 확장자로 mimeType 을 복원한다 —
        // 멀쩡한 raw 가 메타 결함만으로 비복구 실패하는 것을 막는다(key 확장자가 우리가 박은 신뢰값, content-type 은 fallback).
        String mimeType = mimeTypeFromKeyOrStored(key, stored);
        ProductImage image = ProductImage.of(stored.bytes(), mimeType);

        ImageExtraction extraction = productImageExtractor.extract(image);

        // bbox 있으면 크롭 이미지를, 없으면(또는 크롭 불가 포맷) 원본을 결과 이미지로 올린다(READY 불변식: imageUrl 필수).
        byte[] resultBytes = croppedOrOriginal(image, extraction);
        String imageUrl = imageStorage.upload(bucket, resultBytes, "items/" + UUID.randomUUID() + ".png", "image/png");
        log.info("image extract bucket={} key={} croppedUrl={}", bucket, key, imageUrl);

        // 추출 스냅샷(link=null, imageUrl 미채움)에 업로드 결과 URL 을 채워 돌려준다.
        ProductSnapshot s = extraction.snapshot();
        return new ProductSnapshot(s.link(), s.name(), imageUrl, s.currentPrice(), s.currency());
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
        // 크롭 불가 포맷(HEIC/WebP 등 ImageIO 미지원)은 null → 원본으로 폴백.
        return cropped != null ? cropped : image.bytes();
    }
}
