package com.depromeet.piki.extractor.image;

import com.depromeet.piki.extractor.image.domain.BoundingBox;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Optional;
import javax.imageio.ImageIO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * PNG·JPEG 는 ImageIO 로, HEIC·HEIF 는 {@link HeifDecoder} 로 풀어 크롭한다. WebP 처럼 풀 수 없는 포맷과
 * 디코딩 실패는 null 을 돌려 호출자가 크롭을 건너뛰게 한다.
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class ImageCropper {

    private final HeifDecoder heifDecoder;

    public byte[] crop(byte[] bytes, BoundingBox boundingBox) {
        Optional<BufferedImage> decoded = decode(bytes);
        if (decoded.isEmpty()) {
            return null;
        }
        BufferedImage source = decoded.get();

        int cropX = scale(boundingBox.xMin(), source.getWidth());
        int cropY = scale(boundingBox.yMin(), source.getHeight());
        int cropWidth = Math.min(
            Math.max(scale(boundingBox.xMax(), source.getWidth()) - cropX, 1),
            source.getWidth() - cropX);
        int cropHeight = Math.min(
            Math.max(scale(boundingBox.yMax(), source.getHeight()) - cropY, 1),
            source.getHeight() - cropY);

        try {
            BufferedImage cropped = source.getSubimage(cropX, cropY, cropWidth, cropHeight);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            // write 가 false 면 해당 포맷 인코더가 없어 빈 바이트가 나온다 — 빈 이미지를 올리지 않도록 막는다.
            if (!ImageIO.write(cropped, "png", out)) {
                throw new IllegalStateException("PNG 인코더를 찾지 못했다");
            }
            return out.toByteArray();
        } catch (Exception e) {
            log.warn("이미지 크롭 실패: {}", e.getMessage());
            return null;
        }
    }

    /** ImageIO.read 는 디코더가 없으면 예외가 아니라 null 을 준다. 그때만 HEIF 변환을 시도한다. */
    private Optional<BufferedImage> decode(byte[] bytes) {
        try {
            BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(bytes));
            if (decoded != null) {
                return Optional.of(decoded);
            }
        } catch (IOException e) {
            return Optional.empty();
        }
        return heifDecoder.decode(bytes);
    }

    private int scale(int normalized, int dimension) {
        long scaled = (long) normalized * dimension / BoundingBox.NORMALIZED_MAX;
        long clamped = Math.min(Math.max(scaled, 0), dimension - 1L);
        return (int) clamped;
    }
}
