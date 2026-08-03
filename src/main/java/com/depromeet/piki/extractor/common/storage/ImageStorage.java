package com.depromeet.piki.extractor.common.storage;

/**
 * 이미지(OCR) 경로가 쓰는 두 연산만 둔다 — download 는 outbox 재실행 시점에 등록 워커가 적재한 raw 원본을 다시 읽는 용도다.
 * <p>presign·exists·delete·deleteByPrefix 는 등록 수명주기(발급·확인·회수·탈퇴 파기)라 core 소관이다.
 */
public interface ImageStorage {

    /** bucket 은 요청별 파라미터다 — 환경(dev/staging/prod)마다 이미지 버킷이 달라 호출자가 정한다. */
    String upload(String bucket, byte[] bytes, String key, String contentType);

    /** 객체 없음도 장애와 같은 {@link ImageStorageException} 이다 — 없음을 Optional 로 따로 표현하지 않는다. */
    StoredImage download(String bucket, String key);
}
