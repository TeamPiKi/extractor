package com.depromeet.piki.extractor.extraction.gemini;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.depromeet.piki.extractor.extraction.gemini.GeminiGenerateContentResponse.Candidate;
import com.depromeet.piki.extractor.extraction.gemini.GeminiGenerateContentResponse.Content;
import com.depromeet.piki.extractor.extraction.gemini.GeminiGenerateContentResponse.Part;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GeminiGenerateContentResponseTest {

    @Test
    @DisplayName("candidates 가 비어 있으면 noTextPart 예외를 던진다")
    void emptyCandidatesThrows() {
        GeminiGenerateContentResponse response = new GeminiGenerateContentResponse(List.of());

        assertThrows(GeminiApiException.class, response::extractText);
    }

    @Test
    @DisplayName("parts 가 비어 있으면 noTextPart 예외를 던진다")
    void emptyPartsThrows() {
        GeminiGenerateContentResponse response =
            new GeminiGenerateContentResponse(List.of(new Candidate(new Content(List.of()), null)));

        assertThrows(GeminiApiException.class, response::extractText);
    }

    @Test
    @DisplayName("정상 응답은 첫번째 candidate 의 첫번째 part text 를 반환한다")
    void returnsFirstPartText() {
        GeminiGenerateContentResponse response = new GeminiGenerateContentResponse(
            List.of(new Candidate(new Content(List.of(new Part("{\"isProductPage\":true}"))), null))
        );

        assertEquals("{\"isProductPage\":true}", response.extractText());
    }
}
