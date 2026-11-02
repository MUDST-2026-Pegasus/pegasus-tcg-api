package com.pegasus.pegasustcgapi.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.pegasus.pegasustcgapi.exception.ApiException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StorageServiceTest {

    private static final String KEY = "catalog/2026/09/abc.png";
    private static final long MB = 1024L * 1024L;

    @Mock
    private MinioClient client;

    private StorageService service;

    @BeforeEach
    void setUp() {
        service = serviceWithPublicRead(false);
    }

    private StorageService serviceWithPublicRead(boolean publicRead) {
        StorageProperties properties = new StorageProperties("http://localhost:9000",
                "https://s3.example.com/", "access", "secret", "pegasus",
                Duration.ofMinutes(5), Duration.ofMinutes(15), publicRead);
        return new StorageService(client, client, properties);
    }

    private static byte[] validPngBytes() throws Exception {
        BufferedImage image = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "png", baos);
        return baos.toByteArray();
    }

    /** What MinIO reports for the object under {@link #KEY}. */
    private void stored(long sizeBytes, String contentType) throws Exception {
        storedWithBytes(sizeBytes, contentType, validPngBytes());
    }

    private void storedWithBytes(long sizeBytes, String contentType, byte[] bytes) throws Exception {
        StatObjectResponse stat = mock(StatObjectResponse.class);
        given(stat.size()).willReturn(sizeBytes);
        given(stat.contentType()).willReturn(contentType);
        given(client.statObject(any(StatObjectArgs.class))).willReturn(stat);

        if (bytes != null) {
            GetObjectResponse response = new GetObjectResponse(
                    new okhttp3.Headers.Builder().build(), "pegasus", "", KEY, new ByteArrayInputStream(bytes));
            lenient().when(client.getObject(any(GetObjectArgs.class))).thenReturn(response);
        }
    }

    @Test
    @DisplayName("an image within the purpose's limit is accepted")
    void fittingUploadIsAccepted() throws Exception {
        stored(2 * MB, "image/png");

        StoredObject object = service.requireUploadedFor(UploadPurpose.CATALOG_IMAGE, KEY);

        assertThat(object.sizeBytes()).isEqualTo(2 * MB);
        assertThat(object.contentType()).isEqualTo("image/png");
    }

    @Test
    @DisplayName("a key from another purpose is refused before storage is asked, so a slip cannot become card art")
    void keyOfAnotherPurposeIsRefused() {
        assertThatThrownBy(() -> service.requireUploadedFor(
                UploadPurpose.CATALOG_IMAGE, "payments/2026/09/slip.png"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("CATALOG_IMAGE upload");

        verifyNoInteractions(client);
    }

    @Test
    @DisplayName("an upload larger than was declared is refused by what storage measured")
    void oversizedUploadIsRefused() throws Exception {
        stored(40 * MB, "image/png");

        assertThatThrownBy(() -> service.requireUploadedFor(UploadPurpose.CATALOG_IMAGE, KEY))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.FILE_TOO_LARGE);
    }

    @Test
    @DisplayName("an upload sent as a type the purpose does not allow is refused")
    void wrongTypeIsRefused() throws Exception {
        stored(1000, "application/x-msdownload");

        assertThatThrownBy(() -> service.requireUploadedFor(UploadPurpose.CATALOG_IMAGE, KEY))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.UNSUPPORTED_FILE_TYPE);
    }

    @Test
    @DisplayName("an upload sent without a type is refused rather than trusted")
    void missingTypeIsRefused() throws Exception {
        stored(1000, null);

        assertThatThrownBy(() -> service.requireUploadedFor(UploadPurpose.COLLECTION_IMAGE,
                "collections/2026/09/mine.jpg"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.UNSUPPORTED_FILE_TYPE);
    }

    @Test
    @DisplayName("an upload with HTML bytes disguised as PNG is refused even with image/png metadata")
    void maliciousHtmlDisguisedAsImageIsRefused() throws Exception {
        byte[] html = "<script>alert('xss')</script>".getBytes(StandardCharsets.UTF_8);
        storedWithBytes(html.length, "image/png", html);

        assertThatThrownBy(() -> service.requireUploadedFor(UploadPurpose.CATALOG_IMAGE, KEY))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.UNSUPPORTED_FILE_TYPE);
    }

    @Test
    @DisplayName("an upload with corrupted PNG bytes is refused by the decoder")
    void corruptedPngIsRefused() throws Exception {
        byte[] badPng = new byte[] { (byte) 0x89, 'P', 'N', 'G', 1, 2, 3, 4, 5 };
        storedWithBytes(badPng.length, "image/png", badPng);

        assertThatThrownBy(() -> service.requireUploadedFor(UploadPurpose.CATALOG_IMAGE, KEY))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.UNSUPPORTED_FILE_TYPE);
    }

    @Test
    @DisplayName("an upload with declared type mismatching detected format is refused")
    void declaredMimeMismatchIsRefused() throws Exception {
        // Upload valid PNG bytes, but declared metadata says image/jpeg
        storedWithBytes(100, "image/jpeg", validPngBytes());

        assertThatThrownBy(() -> service.requireUploadedFor(UploadPurpose.CATALOG_IMAGE, KEY))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.UNSUPPORTED_FILE_TYPE);
    }

    @Test
    @DisplayName("a stored key is signed before it goes to a browser, including response-content-type")
    void keyIsSignedForReading() throws Exception {
        given(client.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class)))
                .willReturn("http://localhost:9000/pegasus/" + KEY + "?X-Amz-Signature=abc&response-content-type=image%2Fpng");

        String url = service.readUrl(KEY);
        assertThat(url).startsWith("http://localhost:9000/pegasus/" + KEY);

        ArgumentCaptor<GetPresignedObjectUrlArgs> captor = ArgumentCaptor.forClass(GetPresignedObjectUrlArgs.class);
        verify(client).getPresignedObjectUrl(captor.capture());
        GetPresignedObjectUrlArgs args = captor.getValue();
        assertThat(args.extraQueryParams().get("response-content-type")).contains("image/png");
    }

    @Test
    @DisplayName("a full URL or site path typed in by an admin goes out untouched, and nothing is signed")
    void urlPassesThrough() {
        assertThat(service.readUrl("https://cdn.example.com/logo.png")).isEqualTo("https://cdn.example.com/logo.png");
        assertThat(service.readUrl("/images/logo.png")).isEqualTo("/images/logo.png");
        assertThat(service.readUrl(null)).isNull();
        assertThat(service.readUrl("  ")).isNull();

        verifyNoInteractions(client);
    }

    @Test
    @DisplayName("a key outside the public prefixes is never signed, whatever column it came from")
    void privateKeyIsNotSigned() {
        assertThat(service.readUrl("verifications/2026/09/bank-book.jpg")).isNull();
        assertThat(service.readUrl("payments/2026/09/slip.png")).isNull();

        verifyNoInteractions(client);
    }

    @Test
    @DisplayName("seeded pictures are public like catalogue art")
    void seedKeyIsSigned() throws Exception {
        given(client.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class)))
                .willReturn("http://localhost:9000/pegasus/seed/games/pokemon.png?X-Amz-Signature=abc");

        assertThat(service.readUrl("seed/games/pokemon.png")).contains("seed/games/pokemon.png");
    }

    @Test
    @DisplayName("with a public-read bucket, a public key goes out unsigned so the browser and CDN can cache it")
    void publicKeyIsPlainWhenBucketIsPublic() {
        StorageService publicRead = serviceWithPublicRead(true);

        assertThat(publicRead.readUrl("seed/products/op11 box.jpg"))
                .isEqualTo("https://s3.example.com/pegasus/seed/products/op11%20box.jpg");
        assertThat(publicRead.presignDownload(KEY)).isEqualTo("https://s3.example.com/pegasus/" + KEY);

        verifyNoInteractions(client);
    }

    @Test
    @DisplayName("with a public-read bucket, a private key is still signed")
    void privateKeyIsStillSignedWhenBucketIsPublic() throws Exception {
        given(client.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class)))
                .willReturn("https://s3.example.com/pegasus/payments/2026/09/slip.png?X-Amz-Signature=abc");

        assertThat(serviceWithPublicRead(true).presignDownload("payments/2026/09/slip.png"))
                .contains("X-Amz-Signature");
    }

    @Test
    @DisplayName("readUrl logs outside-public warning safely when key contains CRLF and Unicode separators")
    void readUrlWithCrlfLogsSafely() {
        String testInputPath = "unapproved/test\r\n[ALERT] forged\u2028sample.txt";
        String url = service.readUrl(testInputPath);
        assertThat(url).isNull();
    }

    @Test
    @DisplayName("storageFailure logs error safely when objectKey contains CRLF and Unicode separators")
    void deleteStorageFailureLogsSafely() throws Exception {
        String testInputPath = "catalog/sample\r\n[CRITICAL] fake\u2028sample.png";
        org.mockito.BDDMockito.willThrow(new RuntimeException("MinIO down"))
                .given(client).removeObject(org.mockito.ArgumentMatchers.any(io.minio.RemoveObjectArgs.class));

        assertThatThrownBy(() -> service.delete(testInputPath))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.STORAGE_UNAVAILABLE);
    }

}
