package com.depromeet.piki.extractor.image;

import com.depromeet.piki.extractor.image.domain.BoundingBox;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

// 원본 이미지에서 bbox(0~1000 normalized) 영역만 잘라 PNG 바이트로 돌려준다.
// ImageIO 가 디코딩하는 PNG/JPEG 만 지원한다. HEIC/WebP 등 디코딩 불가·실패 시 null(크롭 스킵).
@Component
public class ImageCropper {

    private static final Logger log = LoggerFactory.getLogger(ImageCropper.class);

    public byte[] crop(byte[] bytes, BoundingBox boundingBox) {
        // ImageIO.read 는 디코더가 없으면(HEIC 등) null 을 반환한다 — 예외가 아니라 null 분기.
        BufferedImage source;
        try {
            source = ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (IOException e) {
            return null;
        }
        if (source == null) {
            return null;
        }

        int cropX = scale(boundingBox.xMin(), source.getWidth());
        int cropY = scale(boundingBox.yMin(), source.getHeight());
        // 끝 좌표는 올림이 아니라 폭/높이로 환산하고, 원본 경계를 넘지 않게 클램핑한다.
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

    // normalized(0~1000) 좌표를 픽셀로 환산하고 [0, dimension) 으로 클램핑한다.
    private int scale(int normalized, int dimension) {
        long scaled = (long) normalized * dimension / BoundingBox.NORMALIZED_MAX;
        long clamped = Math.min(Math.max(scaled, 0), dimension - 1L);
        return (int) clamped;
    }
}
