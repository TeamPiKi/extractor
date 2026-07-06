package com.depromeet.piki.extractor.extraction.gemini;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

// PIKI-Server: product/service/gemini/GeminiGenerateContentResponse.kt 포팅.
//
// Gemini generateContent 응답 wire 모델.
//
// Gemini API 는 범용적으로 설계되어 응답이 항상 중첩 리스트 구조로 옴 — candidates -> content -> parts.
// 이 프로젝트에서는 후보 1개·텍스트 파트 1개만 사용하므로 extractText 가 firstOrNull 로 바로 꺼낸다.
//
// urlContextMetadata 는 url_context 도구를 쓰는 흐름에서만 채워지고 정적 HTML in-context 흐름에선 null 이다.
// 원본의 관측용 접근자 urlContextMetadata() 는 이 서비스에서 호출자가 없어 포팅하지 않았다 — wire 필드만 유지한다.
@JsonIgnoreProperties(ignoreUnknown = true)
public record GeminiGenerateContentResponse(
    List<Candidate> candidates
) {

    public String extractText() {
        if (candidates == null || candidates.isEmpty()) {
            throw GeminiApiException.noTextPart();
        }
        Content content = candidates.get(0).content();
        if (content == null) {
            throw GeminiApiException.noTextPart();
        }
        List<Part> parts = content.parts();
        if (parts == null || parts.isEmpty()) {
            throw GeminiApiException.noTextPart();
        }
        String text = parts.get(0).text();
        if (text == null) {
            throw GeminiApiException.noTextPart();
        }
        return text;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Candidate(
        Content content,
        UrlContextMetadata urlContextMetadata
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Content(
        List<Part> parts
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Part(
        String text
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UrlContextMetadata(
        List<UrlMetadata> urlMetadata
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UrlMetadata(
        String retrievedUrl,
        String urlRetrievalStatus
    ) {}
}
