package com.depromeet.piki.extractor.image.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

// 추출 대상 이미지를 표현하는 값 객체.
//
// 빈 바이트 · 미지정 / 미지원 MIME 타입을 of 팩토리에서 차단한다.
// 입력 형식 검증을 도메인 경계에 모아, GeminiProductImageExtractor 같은 외부 어댑터는
// 항상 유효한 이미지만 받는다는 것을 시그니처 수준에서 보장한다.
//
// 이미지 바이트는 메모리에 그대로 보관한다 (extractor 는 S3 에서 받은 raw 바이트를 이 값 객체로 감싼다).
public final class ProductImage {

    // 이미지 추출이 받아들이는 형식 ↔ 스토리지 key 확장자 단일 매핑. Gemini Vision 지원 목록 기준.
    // https://ai.google.dev/gemini-api/docs/vision
    // SUPPORTED_MIME_TYPES(keys)·EXTENSIONS(values)·extensionOf 가 모두 이 map 을 파생해, 지원 포맷 추가가 한 곳으로 끝난다.
    private static final Map<String, String> MIME_TO_EXTENSION = mimeToExtension();

    public static final Set<String> SUPPORTED_MIME_TYPES = Set.copyOf(MIME_TO_EXTENSION.keySet());

    // raw key 검증이 파생하는 지원 확장자 집합 — MIME_TO_EXTENSION 의 치역이라 포맷 추가 시 자동 추종한다.
    public static final Set<String> EXTENSIONS = Set.copyOf(MIME_TO_EXTENSION.values());

    private final byte[] rawBytes;
    private final String mimeType;

    private ProductImage(byte[] rawBytes, String mimeType) {
        this.rawBytes = rawBytes;
        this.mimeType = mimeType;
    }

    // ByteArray 는 가변이므로 방어적 복사본을 노출한다.
    // 호출자가 받은 배열을 변경해도 ProductImage 내부 상태는 불변으로 유지된다.
    public byte[] bytes() {
        return rawBytes.clone();
    }

    public String mimeType() {
        return mimeType;
    }

    // 스토리지 object key 의 확장자. of() 가 SUPPORTED_MIME_TYPES 로 mimeType 을 보장하므로
    // else 는 도달 불가한 불변식 위반(코드 버그)이다.
    public String extension() {
        return extensionOf(mimeType);
    }

    public static ProductImage of(byte[] bytes, String mimeType) {
        // 빈 파일·미지정/미지원 형식은 모두 이미지로 도달 가능한 계약 위반 → 확정 실패(ProductImageException, 422).
        if (bytes.length == 0) {
            throw ProductImageException.emptyImage();
        }
        return new ProductImage(bytes.clone(), normalizeMimeType(mimeType));
    }

    // content-type 을 정규화·검증하고 스토리지 key 확장자를 돌려준다 — 바이트가 아직 없는 이미지 등록 v2 발급 단계에서,
    // 클라가 올릴 이미지의 content-type 만으로 raw key(items/raw/{UUID}.{ext})를 만드는 데 쓴다.
    // of() 와 같은 정규화·검증을 공유한다(미지정 unknownType · 미지원 unsupportedType).
    public static String extensionForMimeType(String mimeType) {
        return extensionOf(normalizeMimeType(mimeType));
    }

    // 스토리지 object key 확장자에서 mimeType 을 복원한다 — extension() 의 역. 이미지 워커가 S3 download 시
    // content-type 메타를 못 받았을 때, 등록 때 key 에 박은 확장자로 mimeType 을 되살리는 fallback 으로 쓴다.
    // 알 수 없는 확장자면 null — 호출자가 이 fallback 실패를 처리한다.
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

    // media type 정규화·검증 — of()·extensionForMimeType 가 공유한다.
    // RFC 상 media type 은 대소문자를 가리지 않고 `;` 뒤에 파라미터가 붙을 수 있어
    // (예: "IMAGE/JPEG", "image/jpeg; charset=utf-8") 정규화 후 비교한다.
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

    // 정규화된 mimeType 의 스토리지 key 확장자. SUPPORTED_MIME_TYPES(=MIME_TO_EXTENSION.keys)로 보장되므로
    // null 은 도달 불가한 코드 버그다.
    private static String extensionOf(String mimeType) {
        String extension = MIME_TO_EXTENSION.get(mimeType);
        if (extension == null) {
            throw new IllegalStateException("지원하지 않는 MIME 타입의 확장자를 요청했다: " + mimeType);
        }
        return extension;
    }

    // LinkedHashMap(삽입순 보존)으로 지원 포맷 열거 순서를 고정한다.
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
