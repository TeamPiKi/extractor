package com.depromeet.piki.extractor.common.storage;

// 저장소에서 읽어온 객체 — 바이트와 content-type. content-type 은 업로드 시 저장한 값으로,
// S3 가 돌려주지 않을 수 있어 nullable 이다(호출자가 null 을 걸러낸다). ProductSnapshot 과 같은 nullable 필드 record 스타일.
public record StoredImage(byte[] bytes, String contentType) {
}
