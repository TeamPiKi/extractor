package com.depromeet.piki.extractor.image;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.depromeet.piki.extractor.image.domain.BoundingBox;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

class HeifDecoderTest {

    private static final String SAMPLE = "/image-sample-887x404.heic";

    private final HeifDecoder decoder = new HeifDecoder(HeifProperties.defaults());

    @Test
    @DisplayName("첫 박스가 ftyp 인 바이트만 HEIF 로 본다")
    void recognizesHeifByFtypBox() throws IOException {
        assertTrue(HeifDecoder.looksLikeHeif(sample()));
        assertFalse(HeifDecoder.looksLikeHeif(new byte[] {1, 2, 3, 4}));
        assertFalse(HeifDecoder.looksLikeHeif(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}));
        assertFalse(HeifDecoder.looksLikeHeif(new byte[0]));
    }

    @Test
    @DisplayName("변환 명령이 없는 환경에서는 빈 값을 돌려준다")
    void returnsEmptyWhenCommandIsMissing() throws IOException {
        assertEquals(Optional.empty(), decoderRunning("no-such-heif-command").decode(sample()));
    }

    @Test
    @DisplayName("변환 명령이 실패로 끝나면 빈 값을 돌려준다")
    void returnsEmptyWhenCommandFails() throws IOException {
        assertEquals(Optional.empty(), decoderRunning("false").decode(sample()));
    }

    @Test
    @DisplayName("변환 명령이 성공으로 끝나도 결과 파일이 없으면 빈 값을 돌려준다")
    void returnsEmptyWhenCommandWritesNothing() throws IOException {
        assertEquals(Optional.empty(), decoderRunning("true").decode(sample()));
    }

    @Test
    @DisplayName("변환 명령이 제한 시간 안에 끝나지 않으면 중단하고 빈 값을 돌려준다")
    void returnsEmptyWhenCommandTimesOut() throws IOException {
        // tail -f 는 입력 파일을 한 번 내보낸 뒤 끝나지 않고 기다린다
        HeifDecoder hanging = new HeifDecoder(new HeifProperties(List.of("tail", "-f"), Duration.ofMillis(300)));

        assertEquals(Optional.empty(), hanging.decode(sample()));
    }

    @Test
    @DisplayName("HEIF 가 아닌 바이트는 빈 값을 돌려준다")
    void returnsEmptyForNonHeifBytes() {
        assertEquals(Optional.empty(), decoder.decode(new byte[] {1, 2, 3, 4}));
    }

    /** 변환 명령은 운영 이미지(Dockerfile)에만 설치된다. 명령이 있는 환경에서만 돌고, 배포 워크플로가 같은 변환을 한 번 더 확인한다. */
    @Test
    @EnabledIf("decoderInstalled")
    @DisplayName("HEIC 사진을 원본 크기 그대로 풀어낸다")
    void decodesHeicAtOriginalSize() throws IOException {
        BufferedImage decoded = decoder.decode(sample()).orElseThrow();

        assertEquals(887, decoded.getWidth());
        assertEquals(404, decoded.getHeight());
    }

    @Test
    @EnabledIf("decoderInstalled")
    @DisplayName("HEIC 사진도 bbox 비율만큼 잘라 PNG 로 돌려준다")
    void cropsHeic() throws IOException {
        byte[] cropped = new ImageCropper(decoder).crop(sample(), new BoundingBox(0, 0, 500, 500));

        assertNotNull(cropped);
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(cropped));
        assertEquals(443, image.getWidth());
        assertEquals(202, image.getHeight());
    }

    private HeifDecoder decoderRunning(String command) {
        return new HeifDecoder(new HeifProperties(List.of(command), Duration.ofSeconds(5)));
    }

    static boolean decoderInstalled() {
        try {
            return new ProcessBuilder("heif-dec", "--version")
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
                .waitFor() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private byte[] sample() throws IOException {
        try (InputStream in = getClass().getResourceAsStream(SAMPLE)) {
            return in.readAllBytes();
        }
    }
}
