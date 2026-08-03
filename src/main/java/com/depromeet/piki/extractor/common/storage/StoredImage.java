package com.depromeet.piki.extractor.common.storage;

/** contentType 은 업로드 때 저장한 값이지만 S3 가 돌려주지 않을 수 있어 nullable 이다 — 호출자가 걸러낸다. */
public record StoredImage(byte[] bytes, String contentType) {
}
