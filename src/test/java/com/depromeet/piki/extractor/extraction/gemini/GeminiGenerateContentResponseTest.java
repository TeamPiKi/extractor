package com.depromeet.piki.extractor.extraction.gemini;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.depromeet.piki.extractor.extraction.gemini.GeminiGenerateContentResponse.Candidate;
import com.depromeet.piki.extractor.extraction.gemini.GeminiGenerateContentResponse.Content;
import com.depromeet.piki.extractor.extraction.gemini.GeminiGenerateContentResponse.ModalityTokenCount;
import com.depromeet.piki.extractor.extraction.gemini.GeminiGenerateContentResponse.Part;
import com.depromeet.piki.extractor.extraction.gemini.GeminiGenerateContentResponse.UsageMetadata;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GeminiGenerateContentResponseTest {

    @Test
    @DisplayName("candidates 가 비어 있으면 noTextPart 예외를 던진다")
    void emptyCandidatesThrows() {
        GeminiGenerateContentResponse response = new GeminiGenerateContentResponse(List.of(), null);

        assertThrows(GeminiApiException.class, response::extractText);
    }

    @Test
    @DisplayName("parts 가 비어 있으면 noTextPart 예외를 던진다")
    void emptyPartsThrows() {
        GeminiGenerateContentResponse response =
            new GeminiGenerateContentResponse(List.of(new Candidate(new Content(List.of()), null)), null);

        assertThrows(GeminiApiException.class, response::extractText);
    }

    @Test
    @DisplayName("정상 응답은 첫번째 candidate 의 첫번째 part text 를 반환한다")
    void returnsFirstPartText() {
        GeminiGenerateContentResponse response = new GeminiGenerateContentResponse(
            List.of(new Candidate(new Content(List.of(new Part("{\"isProductPage\":true}"))), null)),
            null
        );

        assertEquals("{\"isProductPage\":true}", response.extractText());
    }

    @Test
    @DisplayName("modality 내역에서 이미지 토큰만 골라낸다")
    void picksImageTokenCount() {
        UsageMetadata usage = new UsageMetadata(
            1300, 40, 1340,
            List.of(new ModalityTokenCount("TEXT", 180), new ModalityTokenCount("IMAGE", 1120))
        );

        assertEquals(1120, usage.imageTokenCount());
    }

    @Test
    @DisplayName("modality 내역이 없거나 이미지가 없으면 이미지 토큰은 null 이다")
    void imageTokenCountIsNullWithoutDetails() {
        assertNull(new UsageMetadata(180, 40, 220, null).imageTokenCount());
        assertNull(new UsageMetadata(180, 40, 220, List.of(new ModalityTokenCount("TEXT", 180))).imageTokenCount());
    }

    @Test
    @DisplayName("usage 가 없는 응답도 호출부가 분기 없이 읽는다")
    void usageOrEmptyNeverNull() {
        GeminiGenerateContentResponse response = new GeminiGenerateContentResponse(List.of(), null);

        assertNull(response.usageOrEmpty().totalTokenCount());
    }
}
