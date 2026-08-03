package com.depromeet.piki.extractor.image;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.depromeet.piki.extractor.image.domain.BoundingBox;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ImageCropperTest {

    private final ImageCropper cropper = new ImageCropper();

    private byte[] pngBytes(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    @Test
    @DisplayName("정상 bbox 로 크롭하면 normalized 비율만큼의 PNG 를 돌려준다")
    void cropsToNormalizedRatio() throws IOException {
        byte[] bytes = pngBytes(1000, 1000);
        // bbox 는 0~1000 normalized 좌표 — 1000px 원본에서 100~500 은 400px 폭이 된다.
        byte[] cropped = cropper.crop(bytes, new BoundingBox(100, 100, 500, 500));

        assertNotNull(cropped);
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(cropped));
        assertEquals(400, image.getWidth());
        assertEquals(400, image.getHeight());
    }

    @Test
    @DisplayName("디코딩 불가한 바이트(미지원 포맷 등)는 null")
    void undecodableBytesReturnNull() {
        assertNull(cropper.crop(new byte[] {1, 2, 3}, new BoundingBox(100, 100, 500, 500)));
    }

    @Test
    @DisplayName("원본 경계를 넘는 끝 좌표는 클램핑되어 크롭에 성공한다")
    void clampsOutOfBoundsEndCoordinates() throws IOException {
        byte[] bytes = pngBytes(1000, 1000);
        byte[] cropped = cropper.crop(bytes, new BoundingBox(900, 900, 1000, 1000));

        assertNotNull(cropped);
    }
}
