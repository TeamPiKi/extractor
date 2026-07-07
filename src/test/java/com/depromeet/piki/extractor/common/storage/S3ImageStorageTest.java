package com.depromeet.piki.extractor.common.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.depromeet.piki.extractor.common.exception.ExtractionErrorCode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

// download·upload round-trip 과
// 예외 변환을 검증하는 단위테스트. AWS SDK 실호출은 하지 않는다 — S3Client 는 인터페이스라
// 손수 만든 in-memory fake(모킹 라이브러리 금지 컨벤션)로 격리한다. putObject·getObjectAsBytes 는 SDK 의 default
// 메서드라 두 개만 override 하면 되고, 나머지 abstract(serviceName·close)만 no-op 로 채운다.
class S3ImageStorageTest {

    private static final String BUCKET = "piki-images";
    private static final String REGION = "ap-northeast-2";

    private S3ImageStorage storage(S3Client client) {
        return new S3ImageStorage(client, new S3Properties(REGION));
    }

    @Test
    @DisplayName("upload 로 올린 바이트를 같은 bucket·key 로 download 하면 그대로 돌아온다")
    void uploadThenDownloadRoundTrips() {
        InMemoryS3Client client = new InMemoryS3Client();
        S3ImageStorage storage = storage(client);
        byte[] bytes = "hello-image".getBytes(StandardCharsets.UTF_8);

        String url = storage.upload(BUCKET, bytes, "items/raw/42.png", "image/png");

        // 반환 URL: bucket+region 조합(https://{bucket}.s3.{region}.amazonaws.com/{key}) + key 경로 인코딩('/' 구분자 보존).
        assertEquals("https://piki-images.s3.ap-northeast-2.amazonaws.com/items/raw/42.png", url);
        // putObject 가 받은 값: bucket(요청 인자)·key(raw)·contentType 원본 그대로.
        assertEquals(BUCKET, client.lastPutBucket);
        assertEquals("items/raw/42.png", client.lastPutKey);
        assertEquals("image/png", client.lastPutContentType);

        StoredImage stored = storage.download(BUCKET, "items/raw/42.png");
        assertArrayEquals(bytes, stored.bytes());
        assertEquals("image/png", stored.contentType());
    }

    @Test
    @DisplayName("요청 bucket 이 URL host 와 putObject 대상에 반영된다(환경별 버킷)")
    void requestBucketFlowsIntoUrlAndPutObject() {
        InMemoryS3Client client = new InMemoryS3Client();

        String url =
            storage(client).upload("dev-piki-images-250758375457", new byte[] {1}, "k.png", "image/png");

        assertEquals("https://dev-piki-images-250758375457.s3.ap-northeast-2.amazonaws.com/k.png", url);
        assertEquals("dev-piki-images-250758375457", client.lastPutBucket);
    }

    @Test
    @DisplayName("공백·한글이 든 key 는 경로 세그먼트별로 인코딩되고 '/' 구분자는 보존된다")
    void uploadEncodesUrlPathPerSegment() {
        InMemoryS3Client client = new InMemoryS3Client();

        String url = storage(client).upload(BUCKET, new byte[] {1}, "items/사진 1.png", "image/png");

        assertEquals(
            "https://piki-images.s3.ap-northeast-2.amazonaws.com/items/%EC%82%AC%EC%A7%84%201.png", url);
        // key 자체는 raw 로 저장한다(인코딩하지 않는다).
        assertEquals("items/사진 1.png", client.lastPutKey);
    }

    @Test
    @DisplayName("upload 중 S3 예외는 일시 실패(permanent=false, STORAGE_ERROR) ImageStorageException 으로 변환된다")
    void uploadWrapsS3Failure() {
        S3Client failing = new ThrowingS3Client();

        ImageStorageException e =
            assertThrows(
                ImageStorageException.class,
                () -> storage(failing).upload(BUCKET, new byte[] {1}, "k", "image/png"));

        assertFalse(e.permanent());
        assertEquals(ExtractionErrorCode.STORAGE_ERROR, e.code());
    }

    @Test
    @DisplayName("download 중 S3 예외는 일시 실패(permanent=false, STORAGE_ERROR) ImageStorageException 으로 변환된다")
    void downloadWrapsS3Failure() {
        S3Client failing = new ThrowingS3Client();

        ImageStorageException e =
            assertThrows(ImageStorageException.class, () -> storage(failing).download(BUCKET, "missing"));

        assertFalse(e.permanent());
        assertEquals(ExtractionErrorCode.STORAGE_ERROR, e.code());
    }

    // --- fakes (모킹 라이브러리 금지 컨벤션: S3Client 인터페이스를 손수 구현) ---

    // putObject 로 받은 바이트/메타를 (bucket,key) 별로 담고 getObjectAsBytes 로 되돌려주는 in-memory S3.
    private static final class InMemoryS3Client implements S3Client {
        private final Map<String, byte[]> objects = new HashMap<>();
        private final Map<String, String> contentTypes = new HashMap<>();
        private String lastPutBucket;
        private String lastPutKey;
        private String lastPutContentType;

        @Override
        public PutObjectResponse putObject(PutObjectRequest request, RequestBody body) {
            lastPutBucket = request.bucket();
            lastPutKey = request.key();
            lastPutContentType = request.contentType();
            objects.put(ref(request.bucket(), request.key()), readAll(body));
            contentTypes.put(ref(request.bucket(), request.key()), request.contentType());
            return PutObjectResponse.builder().build();
        }

        @Override
        public ResponseBytes<GetObjectResponse> getObjectAsBytes(GetObjectRequest request) {
            byte[] stored = objects.get(ref(request.bucket(), request.key()));
            if (stored == null) {
                throw SdkClientException.create("no such key: " + request.bucket() + "/" + request.key());
            }
            GetObjectResponse response =
                GetObjectResponse.builder()
                    .contentType(contentTypes.get(ref(request.bucket(), request.key())))
                    .build();
            return ResponseBytes.fromByteArray(response, stored);
        }

        private static String ref(String bucket, String key) {
            return bucket + " " + key;
        }

        private static byte[] readAll(RequestBody body) {
            try {
                return body.contentStreamProvider().newStream().readAllBytes();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public String serviceName() {
            return "s3";
        }

        @Override
        public void close() {
        }
    }

    // 모든 오퍼레이션이 SDK 예외로 실패하는 S3 — 예외 변환 경로 검증용.
    private static final class ThrowingS3Client implements S3Client {
        @Override
        public PutObjectResponse putObject(PutObjectRequest request, RequestBody body) {
            throw SdkClientException.create("s3 down");
        }

        @Override
        public ResponseBytes<GetObjectResponse> getObjectAsBytes(GetObjectRequest request) {
            throw SdkClientException.create("s3 down");
        }

        @Override
        public String serviceName() {
            return "s3";
        }

        @Override
        public void close() {
        }
    }
}
