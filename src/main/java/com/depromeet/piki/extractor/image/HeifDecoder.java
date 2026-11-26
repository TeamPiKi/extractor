package com.depromeet.piki.extractor.image;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * HEIC·HEIF 를 libheif 의 변환 명령으로 풀어 {@link BufferedImage} 로 돌려준다. ImageIO 에는 이 포맷의 디코더가 없다.
 *
 * <p>JVM 안에서 네이티브 라이브러리를 부르지 않고 프로세스로 띄운다. Panama 바인딩 플러그인은 JDK 버전에 묶이고,
 * 디코더가 깨진 입력에서 죽으면 서비스가 함께 죽는다. 프로세스면 그 실패가 한 요청의 크롭 생략으로 끝난다.
 *
 * <p>명령이 없는 환경(로컬·CI)에서는 빈 값을 돌려 호출자가 원본을 그대로 쓰게 한다.
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class HeifDecoder {

    private static final int FTYP_OFFSET = 4;
    private static final byte[] FTYP = "ftyp".getBytes(StandardCharsets.US_ASCII);
    private static final int ERROR_LOG_CHARS = 300;

    /**
     * 한 번에 하나만 변환한다. 변환 프로세스의 메모리(10MP 사진에 실측 132MB)가 컨테이너 한도 안에서 JVM 과 겹쳐,
     * 사진 여러 장이 동시에 오면 장수만큼 곱해진다.
     */
    private final Semaphore oneAtATime = new Semaphore(1);

    private final HeifProperties properties;

    /** HEIF 계열은 ISO BMFF 컨테이너라 첫 박스가 ftyp 다. 아닌 바이트로는 프로세스를 띄우지 않는다. */
    static boolean looksLikeHeif(byte[] bytes) {
        if (bytes.length < FTYP_OFFSET + FTYP.length) {
            return false;
        }
        for (int i = 0; i < FTYP.length; i++) {
            if (bytes[FTYP_OFFSET + i] != FTYP[i]) {
                return false;
            }
        }
        return true;
    }

    public Optional<BufferedImage> decode(byte[] bytes) {
        if (!looksLikeHeif(bytes)) {
            return Optional.empty();
        }
        Path workDir = null;
        boolean acquired = false;
        try {
            acquired = oneAtATime.tryAcquire(properties.decodeTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!acquired) {
                log.warn("HEIF 변환 차례를 제한 시간 안에 얻지 못했다");
                return Optional.empty();
            }
            workDir = Files.createTempDirectory("heif-");
            // 변환 프로세스가 권한 없는 사용자로 돌아 누구나 쓸 수 있어야 한다. 생성 때 주면 umask 에 깎여 만든 뒤에 연다
            Files.setPosixFilePermissions(workDir, PosixFilePermissions.fromString("rwxrwxrwx"));
            Path input = workDir.resolve("in.heic");
            Path output = workDir.resolve("out.png");
            Files.write(input, bytes);
            if (!convert(input, output, workDir.resolve("err.txt"))) {
                return Optional.empty();
            }
            return Optional.ofNullable(ImageIO.read(output.toFile()));
        } catch (IOException e) {
            log.warn("HEIF 변환 실패: {}", e.getMessage());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } finally {
            deleteQuietly(workDir);
            if (acquired) {
                oneAtATime.release();
            }
        }
    }

    private boolean convert(Path input, Path output, Path errorLog) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(properties.decoderCommand());
        command.add(input.toString());
        command.add(output.toString());
        ProcessBuilder builder = new ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(errorLog.toFile());
        // 서비스의 시크릿(환경 변수)을 디코더 프로세스에 물려주지 않는다
        builder.environment().clear();
        Process process = builder.start();
        try {
            if (!process.waitFor(properties.decodeTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("HEIF 변환이 제한 시간을 넘겨 중단했다");
                return false;
            }
            if (process.exitValue() != 0 || !Files.exists(output)) {
                log.warn("HEIF 변환 명령이 실패했다 exit={} output={}", process.exitValue(), head(errorLog));
                return false;
            }
            return true;
        } finally {
            // 제한 시간 초과·인터럽트로 빠져나올 때 프로세스가 남지 않게 한다. 죽은 뒤에 임시 디렉터리를 지워야 파일이 다시 생기지 않는다
            if (process.isAlive()) {
                process.destroyForcibly().waitFor(1, TimeUnit.SECONDS);
            }
        }
    }

    private String head(Path errorLog) {
        try {
            String text = Files.readString(errorLog, StandardCharsets.UTF_8).strip();
            return text.length() > ERROR_LOG_CHARS ? text.substring(0, ERROR_LOG_CHARS) : text;
        } catch (IOException e) {
            return "";
        }
    }

    private void deleteQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        } catch (IOException e) {
            log.warn("HEIF 임시 디렉터리를 지우지 못했다: {}", e.getMessage());
        }
    }
}
