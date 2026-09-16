package com.pegasus.pegasustcgapi.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.pegasus.pegasustcgapi.model.RoleCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class UploadPurposeTest {

    @Test
    @DisplayName("catalogue art is admin-only, a payment slip is not")
    void requiredRoleVariesByPurpose() {
        assertThat(UploadPurpose.CATALOG_IMAGE.requiredRole()).isEqualTo(RoleCode.ADMIN);
        assertThat(UploadPurpose.LISTING_IMAGE.requiredRole()).isEqualTo(RoleCode.SELLER);
        assertThat(UploadPurpose.PAYMENT_SLIP.requiredRole()).isNull();
    }

    @Test
    @DisplayName("a PDF is a slip, never a card photo")
    void onlyDocumentPurposesAcceptPdf() {
        assertThat(UploadPurpose.PAYMENT_SLIP.allows("application/pdf")).isTrue();
        assertThat(UploadPurpose.CATALOG_IMAGE.allows("application/pdf")).isFalse();
        assertThat(UploadPurpose.LISTING_IMAGE.allows("image/jpeg")).isTrue();
    }

    @Test
    @DisplayName("the charset browsers append does not change the type")
    void contentTypeParametersAreIgnored() {
        assertThat(UploadPurpose.CATALOG_IMAGE.allows("image/jpeg; charset=binary")).isTrue();
        assertThat(UploadPurpose.CATALOG_IMAGE.extensionFor("IMAGE/PNG")).isEqualTo("png");
    }

    @Test
    @DisplayName("an unknown type has no extension and is refused")
    void unknownTypeIsRejected() {
        assertThat(UploadPurpose.CATALOG_IMAGE.allows("application/x-msdownload")).isFalse();
        assertThat(UploadPurpose.CATALOG_IMAGE.extensionFor("application/x-msdownload")).isNull();
        assertThat(UploadPurpose.CATALOG_IMAGE.allows(null)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(UploadPurpose.class)
    @DisplayName("every purpose has a folder, a limit and at least one allowed type")
    void everyPurposeIsFullyConfigured(UploadPurpose purpose) {
        assertThat(purpose.prefix()).isNotBlank();
        assertThat(purpose.maxBytes()).isPositive();
        assertThat(purpose.allowedContentTypes()).isNotEmpty();
    }
}
