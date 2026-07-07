package com.depromeet.piki.extractor.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.depromeet.piki.extractor.common.storage.ImageStorageException;
import com.depromeet.piki.extractor.common.storage.StoredImage;
import com.depromeet.piki.extractor.image.gemini.GeminiImageResult;
import com.depromeet.piki.extractor.support.IntegrationTestSupport;
import com.depromeet.piki.extractor.support.StubGeminiClient;
import com.depromeet.piki.extractor.support.StubImageStorage;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

// POST /internal/extractions/image 의 HTTP 계약(docs/api-contract.md §2)을 실제 파이프라인
// (download→OCR→crop→upload, 외부 경계 S3(ImageStorage)·GeminiClient 만 stub)으로 검증한다.
// 응답 모양은 link 와 같고(name·imageUrl·currentPrice·currency), imageUrl 은 업로드 결과 URL 이다.
class ExtractionImageIntegrationTest extends IntegrationTestSupport {

    private static final String BUCKET = "dev-piki-images-250758375457";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private StubImageStorage stubImageStorage;

    @Autowired
    private StubGeminiClient stubGeminiClient;

    private MockMvc mockMvc() {
        return MockMvcBuilders.webAppContextSetup(context).build();
    }

    private String body(String key) {
        return "{\"bucket\": \"" + BUCKET + "\", \"key\": \"" + key + "\"}";
    }

    @Test
    @DisplayName("정상 이미지면 OCR 추출 후 크롭 결과를 업로드하고 200 과 업로드 URL 을 imageUrl 로 반환한다")
    void imageSuccess() throws Exception {
        stubGeminiClient.reset();
        stubImageStorage.onDownload = (bucket, key) -> new StoredImage(new byte[] {1, 2, 3}, "image/png");
        // bbox 없이 반환 → 크롭 스킵하고 원본을 업로드(imageUrl 은 항상 채워진다).
        stubGeminiClient.build = request -> new GeminiImageResult("나이키 신발", 89000, "신발", "KRW", null);

        mockMvc().perform(post("/internal/extractions/image")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Correlation-Id", "snapshot-9")
                .content(body("items/raw/abc.png")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("나이키 신발"))
            .andExpect(jsonPath("$.currentPrice").value(89000))
            .andExpect(jsonPath("$.currency").value("KRW"))
            .andExpect(jsonPath("$.imageUrl").value(StubImageStorage.UPLOADED_URL));
    }

    @Test
    @DisplayName("download 가 content-type 메타를 못 줘도 key 확장자로 mimeType 을 복원해 200 으로 끝난다")
    void nullContentTypeRecoversFromKeyExtension() throws Exception {
        // S3 GetObject 가 content-type 메타를 안 싣는 상황 — 등록 때 호출자가 key 에 박은 확장자(.png)로 복원해야
        // 메타 결함이 IMAGE_UNSUPPORTED(비복구 확정 실패)로 새지 않는다. (이 계약은 이 레포가 유일하게 보증한다 —
        // 호출자(PIKI-Server)엔 download 경로가 없다.)
        stubGeminiClient.reset();
        stubImageStorage.onDownload = (bucket, key) -> new StoredImage(new byte[] {1, 2, 3}, null);
        stubGeminiClient.build = request -> new GeminiImageResult("복원 상품", 12000, null, "KRW", null);

        mockMvc().perform(post("/internal/extractions/image")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("items/raw/meta-missing.png")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("복원 상품"));
    }

    @Test
    @DisplayName("bbox 가 있으면 크롭된 이미지가 업로드된다 - 크롭 결과가 업로드 배선으로 실제 이어지는지 검증")
    void boundingBoxCropIsWiredToUpload() throws Exception {
        // ImageCropper 는 실제 빈으로 태운다(단위 테스트는 crop 계산만 봄 — 여기서는 "크롭 결과가 원본 대신
        // 업로드된다"는 오케스트레이션 배선을 고정한다). 800x800 원본에 bbox(100,100,500,500 normalized 0~1000)
        // → 320x320 크롭이 업로드돼야 한다.
        stubGeminiClient.reset();
        stubImageStorage.lastUploadedBytes = null;
        BufferedImage source = new BufferedImage(800, 800, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(source, "png", out);
        stubImageStorage.onDownload = (bucket, key) -> new StoredImage(out.toByteArray(), "image/png");
        stubGeminiClient.build = request ->
            new GeminiImageResult("크롭 상품", 45000, null, "KRW", new GeminiImageResult.BoundingBoxDto(100, 100, 500, 500));

        mockMvc().perform(post("/internal/extractions/image")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("items/raw/crop.png")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.imageUrl").value(StubImageStorage.UPLOADED_URL));

        BufferedImage uploaded = ImageIO.read(new ByteArrayInputStream(stubImageStorage.lastUploadedBytes));
        assertEquals(320, uploaded.getWidth());
        assertEquals(320, uploaded.getHeight());
    }

    @Test
    @DisplayName("미지원 이미지 형식이면 422 IMAGE_UNSUPPORTED 를 반환한다")
    void unsupportedFormat() throws Exception {
        stubGeminiClient.reset();
        // key 확장자 txt·content-type 도 미지원 → ProductImage.of 가 확정 실패로 던진다.
        stubImageStorage.onDownload = (bucket, key) -> new StoredImage(new byte[] {1, 2, 3}, "text/plain");

        mockMvc().perform(post("/internal/extractions/image")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("items/raw/bad.txt")))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("IMAGE_UNSUPPORTED"));
    }

    @Test
    @DisplayName("S3 download 가 실패하면 502 STORAGE_ERROR 를 반환한다 (호출자가 재시도)")
    void storageDownloadError() throws Exception {
        stubGeminiClient.reset();
        stubImageStorage.onDownload = (bucket, key) -> {
            throw ImageStorageException.downloadFailed(new RuntimeException("S3 timeout"));
        };

        mockMvc().perform(post("/internal/extractions/image")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("items/raw/gone.png")))
            .andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.code").value("STORAGE_ERROR"));
    }

    @Test
    @DisplayName("추출이 name 을 못 채우면 422 UNTRUSTWORTHY_VALUE 를 반환한다 (link 와 같은 non-null 규약)")
    void incompleteExtraction() throws Exception {
        stubGeminiClient.reset();
        stubImageStorage.onDownload = (bucket, key) -> new StoredImage(new byte[] {1, 2, 3}, "image/png");
        stubGeminiClient.build = request -> new GeminiImageResult(null, 1000, null, "KRW", null);

        mockMvc().perform(post("/internal/extractions/image")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("items/raw/noname.png")))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("UNTRUSTWORTHY_VALUE"));
    }
}
