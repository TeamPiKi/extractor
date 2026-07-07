package com.depromeet.piki.extractor.common.storage;

// 이미지(OCR) 경로가 쓰는 두 연산:
//   upload   — 크롭 결과를 저장하고 공개 URL 을 돌려준다.
//   download — outbox 재실행 시점에 등록 워커가 적재한 raw 원본을 다시 읽는다.
// presign·exists·delete·deleteByPrefix 는 등록 수명주기(발급·확인·회수·탈퇴 파기)라 PIKI-Server 소관이다.
public interface ImageStorage {

    // 이미지 바이트를 저장소(S3)에 올리고 공개 접근 URL 을 돌려준다.
    // bucket 은 요청별 파라미터다 — 환경(dev/staging/prod)마다 이미지 버킷이 달라 호출자가 정한다.
    String upload(String bucket, byte[] bytes, String key, String contentType);

    // 저장소에서 객체를 바이트·content-type 으로 읽어온다. 객체가 없거나 스토리지 장애면 ImageStorageException(일시 502).
    // bucket 은 요청별 파라미터다(환경마다 버킷이 다름).
    StoredImage download(String bucket, String key);
}
