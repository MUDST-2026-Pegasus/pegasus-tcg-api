package com.pegasus.pegasustcgapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import com.pegasus.pegasustcgapi.dto.PresignUploadRequest;
import com.pegasus.pegasustcgapi.dto.PresignUploadResponse;
import com.pegasus.pegasustcgapi.exception.ApiException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.ForbiddenException;
import com.pegasus.pegasustcgapi.model.RoleCode;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.storage.StorageService;
import com.pegasus.pegasustcgapi.storage.UploadPurpose;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UploadServiceTest {

    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-03-09T10:15:30Z"), ZoneOffset.UTC);

    @Mock
    private StorageService storage;

    private UploadService uploads;

    @BeforeEach
    void setUp() {
        uploads = new UploadService(storage, FIXED);
    }

    private static AuthPrincipal principalWith(RoleCode... roles) {
        return new AuthPrincipal(7L, "ploy@example.com", "ploy", Set.of(roles));
    }

    @Test
    @DisplayName("the key is dated, foldered by purpose and ends in the right extension")
    void keyCarriesPurposeDateAndExtension() {
        given(storage.presignUpload(anyString())).willReturn("http://localhost:9000/signed");
        given(storage.uploadUrlTtlSeconds()).willReturn(300L);

        PresignUploadResponse response = uploads.presign(
                principalWith(RoleCode.ADMIN),
                new PresignUploadRequest(UploadPurpose.CATALOG_IMAGE, "image/jpeg", 1_000L));

        assertThat(response.objectKey())
                .startsWith("catalog/2026/03/")
                .endsWith(".jpg");
        assertThat(response.uploadUrl()).isEqualTo("http://localhost:9000/signed");
        assertThat(response.expiresInSeconds()).isEqualTo(300L);
        assertThat(response.maxBytes()).isEqualTo(UploadPurpose.CATALOG_IMAGE.maxBytes());
    }

    @Test
    @DisplayName("two uploads never collide on one key")
    void keysAreUnique() {
        given(storage.presignUpload(anyString())).willReturn("http://localhost:9000/signed");

        PresignUploadRequest request =
                new PresignUploadRequest(UploadPurpose.CATALOG_IMAGE, "image/png", 10L);
        AuthPrincipal admin = principalWith(RoleCode.ADMIN);

        assertThat(uploads.presign(admin, request).objectKey())
                .isNotEqualTo(uploads.presign(admin, request).objectKey());
    }

    @Test
    @DisplayName("a seller cannot upload catalogue art")
    void purposeRoleIsEnforced() {
        assertThatThrownBy(() -> uploads.presign(
                principalWith(RoleCode.SELLER),
                new PresignUploadRequest(UploadPurpose.CATALOG_IMAGE, "image/jpeg", 10L)))
                .isInstanceOf(ForbiddenException.class)
                .extracting(e -> ((ForbiddenException) e).errorCode())
                .isEqualTo(ErrorCode.ACCESS_DENIED);

        verifyNoInteractions(storage);
    }

    @Test
    @DisplayName("a purpose with no required role is open to any signed-in account")
    void buyerMayUploadAPaymentSlip() {
        given(storage.presignUpload(anyString())).willReturn("http://localhost:9000/signed");

        PresignUploadResponse response = uploads.presign(
                principalWith(RoleCode.BUYER),
                new PresignUploadRequest(UploadPurpose.PAYMENT_SLIP, "application/pdf", 10L));

        assertThat(response.objectKey()).startsWith("payments/").endsWith(".pdf");
    }

    @Test
    @DisplayName("an executable is refused before any URL is signed")
    void unsupportedTypeIsRejected() {
        assertThatThrownBy(() -> uploads.presign(
                principalWith(RoleCode.ADMIN),
                new PresignUploadRequest(UploadPurpose.CATALOG_IMAGE, "application/x-msdownload", 10L)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.UNSUPPORTED_FILE_TYPE);

        verifyNoInteractions(storage);
    }

    @Test
    @DisplayName("an oversized file is refused before it is transferred")
    void oversizedFileIsRejected() {
        long overLimit = UploadPurpose.CATALOG_IMAGE.maxBytes() + 1;

        assertThatThrownBy(() -> uploads.presign(
                principalWith(RoleCode.ADMIN),
                new PresignUploadRequest(UploadPurpose.CATALOG_IMAGE, "image/jpeg", overLimit)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.FILE_TOO_LARGE);

        verifyNoInteractions(storage);
    }
}
