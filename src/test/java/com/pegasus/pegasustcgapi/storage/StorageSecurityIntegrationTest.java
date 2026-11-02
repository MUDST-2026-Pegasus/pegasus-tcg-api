package com.pegasus.pegasustcgapi.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.pegasus.pegasustcgapi.exception.ApiException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * End-to-end integration tests verifying real object storage behavior:
 * 1. Malicious payloads disguised with image MIME-types are refused by magic bytes / decoder check.
 * 2. Valid images are accepted and inspected.
 * 3. MinIO HTTP responses enforce X-Content-Type-Options: nosniff and canonical Content-Type,
 *    preventing browser-side MIME sniffing.
 */
class StorageSecurityIntegrationTest {

    private MinioClient minioClient;
    private StorageService storageService;
    private StorageProperties properties;
    private boolean storageAvailable = false;
    private String testKey;

    @BeforeEach
    void setUp() {
        String endpoint = System.getenv("MINIO_ENDPOINT");
        if (endpoint == null || endpoint.isBlank()) {
            endpoint = "http://localhost:9000";
        }
        String accessKey = System.getenv("MINIO_ACCESS_KEY");
        if (accessKey == null || accessKey.isBlank()) {
            accessKey = "minioadmin";
        }
        String secretKey = System.getenv("MINIO_SECRET_KEY");
        if (secretKey == null || secretKey.isBlank()) {
            secretKey = "minioadmin";
        }
        String bucket = "pegasus";

        properties = new StorageProperties(
                endpoint, endpoint, accessKey, secretKey, bucket,
                Duration.ofMinutes(5), Duration.ofMinutes(15), false);

        minioClient = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();

        try {
            storageAvailable = minioClient.bucketExists(
                    BucketExistsArgs.builder().bucket(bucket).build());
        } catch (Exception e) {
            storageAvailable = false;
        }

        if (storageAvailable) {
            storageService = new StorageService(minioClient, minioClient, properties);
        }
    }

    @AfterEach
    void tearDown() {
        if (storageAvailable && testKey != null) {
            try {
                minioClient.removeObject(RemoveObjectArgs.builder()
                        .bucket(properties.bucket())
                        .object(testKey)
                        .build());
            } catch (Exception ignored) {
            }
        }
    }

    private static byte[] validPngBytes() throws Exception {
        BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "png", baos);
        return baos.toByteArray();
    }

    @Test
    @DisplayName("malicious HTML payload disguised as image/png is blocked before binding to data")
    void maliciousHtmlDisguisedAsImageIsBlockedByStorageValidation() throws Exception {
        assumeTrue(storageAvailable, "MinIO object storage is not available, skipping integration test");

        testKey = "catalog/2026/11/" + UUID.randomUUID() + ".png";
        byte[] htmlPayload = "<!DOCTYPE html><html><body><script>alert('xss')</script></body></html>"
                .getBytes(StandardCharsets.UTF_8);

        // Attacker uploads HTML bytes with Content-Type: image/png
        minioClient.putObject(PutObjectArgs.builder()
                .bucket(properties.bucket())
                .object(testKey)
                .contentType("image/png")
                .stream(new ByteArrayInputStream(htmlPayload), (long) htmlPayload.length, -1L)
                .build());

        // StorageService must refuse to bind this key
        assertThatThrownBy(() -> storageService.requireUploadedFor(UploadPurpose.CATALOG_IMAGE, testKey))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.UNSUPPORTED_FILE_TYPE);
    }

    @Test
    @DisplayName("valid PNG upload is accepted and canonical content type returned")
    void validPngUploadIsAccepted() throws Exception {
        assumeTrue(storageAvailable, "MinIO object storage is not available, skipping integration test");

        testKey = "catalog/2026/11/" + UUID.randomUUID() + ".png";
        byte[] png = validPngBytes();

        minioClient.putObject(PutObjectArgs.builder()
                .bucket(properties.bucket())
                .object(testKey)
                .contentType("image/png")
                .stream(new ByteArrayInputStream(png), (long) png.length, -1L)
                .build());

        StoredObject object = storageService.requireUploadedFor(UploadPurpose.CATALOG_IMAGE, testKey);
        assertThat(object.key()).isEqualTo(testKey);
        assertThat(object.contentType()).isEqualTo("image/png");
        assertThat(object.sizeBytes()).isEqualTo(png.length);
    }

    @Test
    @DisplayName("storage response headers enforce nosniff and canonical content-type")
    void storageResponseHeadersEnforceContentTypeAndNosniff() throws Exception {
        assumeTrue(storageAvailable, "MinIO object storage is not available, skipping integration test");

        testKey = "catalog/2026/11/" + UUID.randomUUID() + ".png";
        byte[] png = validPngBytes();

        minioClient.putObject(PutObjectArgs.builder()
                .bucket(properties.bucket())
                .object(testKey)
                .contentType("image/png")
                .stream(new ByteArrayInputStream(png), (long) png.length, -1L)
                .build());

        String presignedDownloadUrl = storageService.presignDownload(testKey);
        assertThat(presignedDownloadUrl).contains("response-content-type=image%2Fpng");

        HttpURLConnection connection = (HttpURLConnection) URI.create(presignedDownloadUrl).toURL().openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);

        int responseCode = connection.getResponseCode();
        assertThat(responseCode).isEqualTo(200);

        String nosniffHeader = connection.getHeaderField("X-Content-Type-Options");
        assertThat(nosniffHeader).isNotNull();
        assertThat(nosniffHeader).isEqualToIgnoringCase("nosniff");

        String contentTypeHeader = connection.getHeaderField("Content-Type");
        assertThat(contentTypeHeader).isNotNull();
        assertThat(contentTypeHeader).contains("image/png");

        connection.disconnect();
    }
}
