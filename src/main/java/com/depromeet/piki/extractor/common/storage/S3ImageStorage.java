package com.depromeet.piki.extractor.common.storage;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

// AWS SDK 예외(네트워크·권한·객체 없음·timeout)를 계약 예외 ImageStorageException(일시 502)으로 변환한다.
// presign·exists·delete·deleteByPrefix 는 등록 수명주기라 PIKI-Server 소관이다.
@Component
public class S3ImageStorage implements ImageStorage {

    private final S3Client s3Client;
    private final S3Properties s3Properties;

    public S3ImageStorage(S3Client s3Client, S3Properties s3Properties) {
        this.s3Client = s3Client;
        this.s3Properties = s3Properties;
    }

    @Override
    public String upload(String bucket, byte[] bytes, String key, String contentType) {
        // AWS SDK 예외를 계약 예외(일시 502)로 변환해 호출부가 일관되게 다루게 한다. URL 조립까지 같은 경계 안에 둔다.
        try {
            // S3 object key 는 raw 로 저장하고, 반환 URL 만 경로 인코딩한다.
            s3Client.putObject(
                PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .contentType(contentType)
                    .build(),
                RequestBody.fromBytes(bytes));
            return publicUrl(bucket, key);
        } catch (RuntimeException e) {
            throw ImageStorageException.uploadFailed(e);
        }
    }

    @Override
    public StoredImage download(String bucket, String key) {
        // AWS SDK 예외(네트워크·권한·객체 없음)를 일시 502 로 변환한다 — 워커가 일시 오류로 받아 PROCESSING 유지(recover 재시도).
        try {
            ResponseBytes<GetObjectResponse> response =
                s3Client.getObjectAsBytes(
                    GetObjectRequest.builder().bucket(bucket).key(key).build());
            return new StoredImage(response.asByteArray(), response.response().contentType());
        } catch (RuntimeException e) {
            throw ImageStorageException.downloadFailed(e);
        }
    }

    // 반환 public URL 을 요청 bucket + config region 으로 조합한다 (환경별 버킷이 달라 config publicBaseUrl 대신 요청 bucket 을 쓴다).
    // 형식: https://{bucket}.s3.{region}.amazonaws.com/{경로인코딩된 key}. bucket 은 DNS-safe 라 인코딩하지 않고 host 로 그대로 둔다.
    private String publicUrl(String bucket, String key) {
        return "https://%s.s3.%s.amazonaws.com/%s".formatted(bucket, s3Properties.region(), encodePath(key));
    }

    // key 의 각 경로 세그먼트를 URL 인코딩한다 ('/' 는 구분자로 보존). 공백/한글/예약문자 키도 접근 가능한 URL 이 되게 한다
    // (URLEncoder 의 '+' 는 path 에선 '%20' 이어야 한다). split(-1)로 후행 빈 세그먼트도 보존해 key 와 URL 경로가 1:1 로 대응한다.
    private static String encodePath(String key) {
        return Arrays.stream(key.split("/", -1))
            .map(segment -> URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20"))
            .collect(Collectors.joining("/"));
    }
}
