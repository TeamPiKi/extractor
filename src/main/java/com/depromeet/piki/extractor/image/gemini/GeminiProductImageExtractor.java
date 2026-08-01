package com.depromeet.piki.extractor.image.gemini;

import com.depromeet.piki.extractor.extraction.gemini.GeminiClient;
import com.depromeet.piki.extractor.image.ImageExtraction;
import com.depromeet.piki.extractor.image.ProductImageExtractor;
import com.depromeet.piki.extractor.image.domain.ProductImage;
import java.util.Base64;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
public class GeminiProductImageExtractor implements ProductImageExtractor {

    private final GeminiClient geminiClient;

    @Override
    public ImageExtraction extract(ProductImage image) {
        String base64Image = Base64.getEncoder().encodeToString(image.bytes());
        GeminiImageRequest request = GeminiImageRequest.forImageAnalysis(base64Image, image.mimeType());
        return geminiClient.generateContent(request, GeminiImageResult.class).toImageExtraction();
    }
}
