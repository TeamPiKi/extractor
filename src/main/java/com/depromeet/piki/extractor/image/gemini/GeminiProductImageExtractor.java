package com.depromeet.piki.extractor.image.gemini;

import com.depromeet.piki.extractor.extraction.gemini.GeminiClient;
import com.depromeet.piki.extractor.image.ImageExtraction;
import com.depromeet.piki.extractor.image.ProductImageExtractor;
import com.depromeet.piki.extractor.image.domain.ProductImage;
import java.util.Base64;
import org.springframework.stereotype.Component;

// 이미지 바이트를 base64 로 인코딩해 Gemini generateContent 로 보내고, 결과를 ImageExtraction 으로 옮긴다.
// GeminiClient 인터페이스에만 의존해 통합 테스트가 LLM 호출을 stub 으로 격리할 수 있다 (link 경로와 동일).
@Component
public class GeminiProductImageExtractor implements ProductImageExtractor {

    private final GeminiClient geminiClient;

    public GeminiProductImageExtractor(GeminiClient geminiClient) {
        this.geminiClient = geminiClient;
    }

    @Override
    public ImageExtraction extract(ProductImage image) {
        String base64Image = Base64.getEncoder().encodeToString(image.bytes());
        GeminiImageRequest request = GeminiImageRequest.forImageAnalysis(base64Image, image.mimeType());
        return geminiClient.generateContent(request, GeminiImageResult.class).toImageExtraction();
    }
}
