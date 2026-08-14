package com.depromeet.piki.extractor.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
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

/**
 * {@code POST /internal/extractions/image} 의 HTTP 계약(docs/api-contract.md §2)을 실제 파이프라인
 * (download→OCR→crop→upload)으로 검증한다. 외부 경계인 S3({@code ImageStorage})·GeminiClient 만 stub 이다.
 */
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
        // bbox 없이 반환 → 크롭을 건너뛰고 원본을 업로드한다.
        stubGeminiClient.build = request -> new GeminiImageResult("나이키 신발", 89000, "신발", "KRW", null);

        mockMvc().perform(post("/internal/extractions/image")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Correlation-Id", "snapshot-9")
                .content(body("items/raw/abc.png")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("나이키 신발"))
            .andExpect(jsonPath("$.currentPrice").value(89000))
            .andExpect(jsonPath("$.currency").value("KRW"))
            .andExpect(jsonPath("$.imageUrl").value(StubImageStorage.UPLOADED_URL))
            // additive 계약(core#825): 이미지 경로는 원본 URL 이 없어 finalUrl 이 명시적 null 이고, 추출은 Gemini 라 method=LLM.
            .andExpect(jsonPath("$.finalUrl").value(nullValue()))
            .andExpect(jsonPath("$.method").value("LLM"));
    }

    @Test
    @DisplayName("download 가 content-type 메타를 못 줘도 key 확장자로 mimeType 을 복원해 200 으로 끝난다")
    void nullContentTypeRecoversFromKeyExtension() throws Exception {
        // S3 GetObject 가 content-type 메타를 안 싣는 상황 — 등록 때 호출자가 key 에 박은 확장자(.png)로 복원해야
        // 메타 결함이 IMAGE_UNSUPPORTED(비복구 확정 실패)로 새지 않는다. 호출자(core)엔 download 경로가 없어
        // 이 계약은 이 레포만 보증한다.
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
        // ImageCropper 는 실제 빈으로 태운다 — crop 계산이 아니라 "크롭 결과가 원본 대신 업로드된다"는
        // 오케스트레이션 배선을 고정하는 것이 목적이다. 800x800 원본 + bbox(0~1000 정규화) → 320x320.
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
            .andExpect(jsonPath("$.imageUrl").value(StubImageStorage.UPLOADED_URL))
            // additive 계약(core#825): 이미지 경로는 원본 URL 이 없어 finalUrl 이 명시적 null 이고, 추출은 Gemini 라 method=LLM.
            .andExpect(jsonPath("$.finalUrl").value(nullValue()))
            .andExpect(jsonPath("$.method").value("LLM"));

        BufferedImage uploaded = ImageIO.read(new ByteArrayInputStream(stubImageStorage.lastUploadedBytes));
        assertEquals(320, uploaded.getWidth());
        assertEquals(320, uploaded.getHeight());
        // 크롭이 실제로 일어났을 때만 PNG 다 — 아래 HEIC 케이스와 짝을 이루는 대조군.
        assertEquals("image/png", stubImageStorage.lastUploadedContentType);
        assertTrue(stubImageStorage.lastUploadedKey.endsWith(".png"));
    }

    @Test
    @DisplayName("크롭할 수 없는 포맷은 원본 확장자·content-type 으로 올린다 - png 로 위장하지 않는다")
    void uploadsOriginalFormatWhenCropIsImpossible() throws Exception {
        // HEIC 은 ImageIO 에 디코더가 없어 크롭이 건너뛰어진다(의도된 fallback). 그때 원본 바이트를 .png ·
        // image/png 로 올리면 브라우저가 렌더링하지 못하는 파일이 저장된다 — prod 에서 실제로 그랬다(#35).
        // 등록이 허용하는 5개 포맷 중 webp·heic·heif 셋이 이 경로를 탄다.
        stubGeminiClient.reset();
        stubImageStorage.lastUploadedKey = null;
        stubImageStorage.lastUploadedContentType = null;
        byte[] heicBytes = {1, 2, 3, 4};
        stubImageStorage.onDownload = (bucket, key) -> new StoredImage(heicBytes, "image/heic");
        stubGeminiClient.build = request ->
            new GeminiImageResult("사진 상품", 30000, null, "KRW", new GeminiImageResult.BoundingBoxDto(100, 100, 500, 500));

        mockMvc().perform(post("/internal/extractions/image")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("items/raw/photo.heic")))
            .andExpect(status().isOk());

        assertEquals("image/heic", stubImageStorage.lastUploadedContentType);
        assertTrue(stubImageStorage.lastUploadedKey.endsWith(".heic"));
        assertArrayEquals(heicBytes, stubImageStorage.lastUploadedBytes, "크롭이 불가능하면 원본 바이트가 그대로 올라간다");
    }

    @Test
    @DisplayName("미지원 이미지 형식이면 422 IMAGE_UNSUPPORTED 를 반환한다")
    void unsupportedFormat() throws Exception {
        stubGeminiClient.reset();
        // 확장자·content-type 둘 다 미지원이라 확장자 복원 경로로도 살아나지 못한다.
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
    @DisplayName("추출이 name 을 못 채워도 200 으로 채운 값을 반환한다 - 호출자가 INCOMPLETE 로 받는다")
    void incompleteExtraction() throws Exception {
        stubGeminiClient.reset();
        stubImageStorage.onDownload = (bucket, key) -> new StoredImage(new byte[] {1, 2, 3}, "image/png");
        stubGeminiClient.build = request -> new GeminiImageResult(null, 1000, null, "KRW", null);

        mockMvc().perform(post("/internal/extractions/image")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("items/raw/noname.png")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value(nullValue()))
            .andExpect(jsonPath("$.currentPrice").value(1000))
            // 이미지 경로는 추출 결과물을 올려 imageUrl 이 항상 채워진다 — 사용자가 채울 것은 이름뿐이다.
            .andExpect(jsonPath("$.imageUrl").value(notNullValue()));
    }

    @Test
    @DisplayName("이미지 경로는 추출값이 전부 비어도 업로드 결과가 있어 200 이다 - 사용자가 이름·가격을 채운다")
    void noExtractedValue() throws Exception {
        stubGeminiClient.reset();
        stubImageStorage.onDownload = (bucket, key) -> new StoredImage(new byte[] {1, 2, 3}, "image/png");
        // 가격·이름 모두 없음. imageUrl 은 업로드 결과라 채워지므로, 값 0개를 만들려면 업로드 자체가 없어야 하는데
        // 이미지 경로에는 그 상태가 없다 — 그래서 이 판정은 link 경로에서 표면화된다(ExtractionLinkIntegrationTest).
        stubGeminiClient.build = request -> new GeminiImageResult(null, null, null, null, null);

        mockMvc().perform(post("/internal/extractions/image")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("items/raw/empty.png")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.imageUrl").value(notNullValue()));
    }
}
