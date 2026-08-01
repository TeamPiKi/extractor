package com.depromeet.piki.extractor.common.storage;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/** AWS SDK 예외(네트워크·권한·객체 없음·timeout)를 계약 예외 {@link ImageStorageException} 으로 바꾸는 경계. */
@RequiredArgsConstructor
@Component
public class S3ImageStorage implements ImageStorage {

    private final S3Client s3Client;
    private final S3Properties s3Properties;

    @Override
    public String upload(String bucket, byte[] bytes, String key, String contentType) {
        // SDK 예외는 종류를 가리지 않고 전부 계약 예외로 바꾼다 — 그래서 RuntimeException 을 넓게 잡는다.
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
        try {
            ResponseBytes<GetObjectResponse> response =
                s3Client.getObjectAsBytes(
                    GetObjectRequest.builder().bucket(bucket).key(key).build());
            return new StoredImage(response.asByteArray(), response.response().contentType());
        } catch (RuntimeException e) {
            throw ImageStorageException.downloadFailed(e);
        }
    }

    /**
     * 환경별 버킷이 달라 설정된 base URL 이 아니라 요청 bucket 으로 조합한다.
     * <p>bucket 은 DNS-safe 라 인코딩하지 않고 host 에 그대로 두고, key 만 경로 인코딩한다.
     */
    private String publicUrl(String bucket, String key) {
        return "https://%s.s3.%s.amazonaws.com/%s".formatted(bucket, s3Properties.region(), encodePath(key));
    }

    /**
     * 공백·한글·예약문자가 든 key 도 접근 가능한 URL 이 되게 세그먼트 단위로 인코딩한다({@code '/'} 는 구분자로 보존).
     * <p>{@code URLEncoder} 는 form 인코딩이라 공백을 {@code '+'} 로 내지만 path 에선 {@code %20} 이어야 한다.
     * 음수 limit 으로 split 해 후행 빈 세그먼트까지 보존하고, 그래서 key 와 URL 경로가 1:1 로 대응한다.
     */
    private static String encodePath(String key) {
        return Arrays.stream(key.split("/", -1))
            .map(segment -> URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20"))
            .collect(Collectors.joining("/"));
    }
}
