package com.depromeet.piki.extractor.image.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 입력 형식 검증(빈 바이트·미지정/미지원 MIME)을 이 도메인 경계에 모아, {@code GeminiProductImageExtractor} 같은
 * 외부 어댑터는 항상 유효한 이미지만 받는다는 것을 시그니처 수준에서 보장한다.
 *
 * <p>이미지 바이트는 스트리밍 없이 메모리에 그대로 보관한다.
 */
public final class ProductImage {

    /**
     * 이미지 추출이 받아들이는 형식 ↔ 스토리지 key 확장자 단일 매핑. Gemini Vision 지원 목록 기준이며,
     * {@code SUPPORTED_MIME_TYPES}(keys)·{@code EXTENSIONS}(values)·{@code extensionOf} 가 모두 이 map 을
     * 파생해 지원 포맷 추가가 한 곳으로 끝난다.
     *
     * @see <a href="https://ai.google.dev/gemini-api/docs/vision">Gemini API image understanding</a>
     */
    private static final Map<String, String> MIME_TO_EXTENSION = mimeToExtension();

    public static final Set<String> SUPPORTED_MIME_TYPES = Set.copyOf(MIME_TO_EXTENSION.keySet());

    public static final Set<String> EXTENSIONS = Set.copyOf(MIME_TO_EXTENSION.values());

    private final byte[] rawBytes;
    private final String mimeType;

    private ProductImage(byte[] rawBytes, String mimeType) {
        this.rawBytes = rawBytes;
        this.mimeType = mimeType;
    }

    /** 배열은 가변이므로 방어적 복사본을 노출한다 — 호출자가 받은 배열을 변경해도 내부 상태는 불변이다. */
    public byte[] bytes() {
        return rawBytes.clone();
    }

    public String mimeType() {
        return mimeType;
    }

    public String extension() {
        return extensionOf(mimeType);
    }

    public static ProductImage of(byte[] bytes, String mimeType) {
        if (bytes.length == 0) {
            throw ProductImageException.emptyImage();
        }
        return new ProductImage(bytes.clone(), normalizeMimeType(mimeType));
    }

    /**
     * 바이트가 아직 없는 이미지 등록 발급 단계에서, 클라가 올릴 content-type 만으로
     * raw key({@code items/raw/{UUID}.{ext}})를 만드는 데 쓴다 — {@code of()} 와 같은 정규화·검증을 공유한다.
     */
    public static String extensionForMimeType(String mimeType) {
        return extensionOf(normalizeMimeType(mimeType));
    }

    /** {@code extension()} 의 역 — 알 수 없는 확장자면 null 이고, 호출자가 이 fallback 실패를 처리한다. */
    public static String mimeTypeOfExtension(String extension) {
        return switch (extension.toLowerCase(Locale.ROOT)) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "webp" -> "image/webp";
            case "heic" -> "image/heic";
            case "heif" -> "image/heif";
            default -> null;
        };
    }

    /**
     * RFC 상 media type 은 대소문자를 가리지 않고 {@code ;} 뒤에 파라미터가 붙을 수 있어
     * (예: "IMAGE/JPEG", "image/jpeg; charset=utf-8") 정규화 후 비교한다.
     */
    private static String normalizeMimeType(String mimeType) {
        if (mimeType == null) {
            throw ProductImageException.unknownType();
        }
        int semicolon = mimeType.indexOf(';');
        String beforeSemicolon = semicolon >= 0 ? mimeType.substring(0, semicolon) : mimeType;
        String type = beforeSemicolon.trim().toLowerCase(Locale.ROOT);
        if (!SUPPORTED_MIME_TYPES.contains(type)) {
            throw ProductImageException.unsupportedType();
        }
        return type;
    }

    /**
     * mimeType 은 {@code of()}·{@code extensionForMimeType} 가 {@code SUPPORTED_MIME_TYPES}
     * (={@code MIME_TO_EXTENSION} 의 keys)로 보장하므로, null 은 도달 불가한 코드 버그다.
     */
    private static String extensionOf(String mimeType) {
        String extension = MIME_TO_EXTENSION.get(mimeType);
        if (extension == null) {
            throw new IllegalStateException("지원하지 않는 MIME 타입의 확장자를 요청했다: " + mimeType);
        }
        return extension;
    }

    /** LinkedHashMap 으로 지원 포맷의 선언 순서를 보존한다. */
    private static Map<String, String> mimeToExtension() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("image/png", "png");
        map.put("image/jpeg", "jpg");
        map.put("image/webp", "webp");
        map.put("image/heic", "heic");
        map.put("image/heif", "heif");
        return Collections.unmodifiableMap(map);
    }
}
