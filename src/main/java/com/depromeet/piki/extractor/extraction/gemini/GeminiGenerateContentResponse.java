package com.depromeet.piki.extractor.extraction.gemini;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Gemini generateContent 응답 wire 모델. Gemini API 는 범용적으로 설계되어 응답이 항상 중첩 리스트
 * ({@code candidates -> content -> parts})로 오지만, 이 프로젝트는 후보 1개·텍스트 파트 1개만 쓴다.
 *
 * <p>urlContextMetadata 는 url_context 도구를 쓰는 흐름에서만 채워지고 정적 HTML in-context 흐름에선 null 이다
 * — 관측용으로 wire 필드만 유지하고, 읽는 호출자는 없다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GeminiGenerateContentResponse(
    List<Candidate> candidates,
    UsageMetadata usageMetadata
) {

    /** 응답이 usage 를 안 실어 보내는 경우(구버전·부분 실패)를 호출부가 분기하지 않게 빈 값으로 좁힌다. */
    public UsageMetadata usageOrEmpty() {
        return usageMetadata != null ? usageMetadata : UsageMetadata.EMPTY;
    }

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

    /**
     * 호출당 토큰 사용량. 비용 추적과 {@code media_resolution} 같은 설정 변경의 근거로 쓴다 — 문서상 기본값이
     * 무엇인지와 별개로, 실제로 이미지에 몇 토큰이 붙는지는 이 값으로만 확인된다.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UsageMetadata(
        Integer promptTokenCount,
        Integer candidatesTokenCount,
        Integer totalTokenCount,
        List<ModalityTokenCount> promptTokensDetails
    ) {

        static final UsageMetadata EMPTY = new UsageMetadata(null, null, null, null);

        /** 입력 토큰 중 이미지 몫. modality 별 내역이 없으면 null 이고, 로그에선 그대로 비워 둔다. */
        public Integer imageTokenCount() {
            if (promptTokensDetails == null) {
                return null;
            }
            return promptTokensDetails.stream()
                .filter(detail -> "IMAGE".equalsIgnoreCase(detail.modality()))
                .map(ModalityTokenCount::tokenCount)
                .findFirst()
                .orElse(null);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ModalityTokenCount(
        String modality,
        Integer tokenCount
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
