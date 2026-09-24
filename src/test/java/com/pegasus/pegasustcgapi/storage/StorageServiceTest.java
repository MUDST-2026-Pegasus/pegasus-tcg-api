package com.pegasus.pegasustcgapi.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.pegasus.pegasustcgapi.exception.ApiException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
        StorageProperties properties = new StorageProperties("http://localhost:9000",
                "http://localhost:9000", "access", "secret", "pegasus",
                Duration.ofMinutes(5), Duration.ofMinutes(15));
        service = new StorageService(client, client, properties);
    }

    /** What MinIO reports for the object under {@link #KEY}. */
    private void stored(long sizeBytes, String contentType) throws Exception {
        StatObjectResponse stat = mock(StatObjectResponse.class);
        given(stat.size()).willReturn(sizeBytes);
        given(stat.contentType()).willReturn(contentType);
        given(client.statObject(any(StatObjectArgs.class))).willReturn(stat);
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
    @DisplayName("a stored key is signed before it goes to a browser")
    void keyIsSignedForReading() throws Exception {
        given(client.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class)))
                .willReturn("http://localhost:9000/pegasus/" + KEY + "?X-Amz-Signature=abc");

        assertThat(service.readUrl(KEY)).startsWith("http://localhost:9000/pegasus/" + KEY);
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
}
