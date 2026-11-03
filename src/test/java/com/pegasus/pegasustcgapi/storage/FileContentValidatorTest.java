package com.pegasus.pegasustcgapi.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pegasus.pegasustcgapi.exception.ApiException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FileContentValidatorTest {

    private FileContentValidator validator;

    @BeforeEach
    void setUp() {
        validator = new FileContentValidator();
    }

    private byte[] createTestImage(String formatName, int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, formatName, baos);
        return baos.toByteArray();
    }

    private byte[] createTestWebpVP8X(int width, int height) {
        // VP8X header (10 bytes chunk payload):
        // bytes 0..3: RIFF
        // bytes 4..7: file size - 8 (LE)
        // bytes 8..11: WEBP
        // bytes 12..15: VP8X
        // bytes 16..19: chunk size (10 LE)
        // byte 20: flags (0)
        // bytes 21..23: reserved
        // bytes 24..26: width - 1 (24-bit LE)
        // bytes 27..29: height - 1 (24-bit LE)
        byte[] data = new byte[30];
        data[0] = 'R'; data[1] = 'I'; data[2] = 'F'; data[3] = 'F';
        int fileSizeMinus8 = 22;
        data[4] = (byte) (fileSizeMinus8 & 0xFF);
        data[5] = (byte) ((fileSizeMinus8 >> 8) & 0xFF);
        data[6] = 0; data[7] = 0;
        data[8] = 'W'; data[9] = 'E'; data[10] = 'B'; data[11] = 'P';
        data[12] = 'V'; data[13] = 'P'; data[14] = '8'; data[15] = 'X';
        data[16] = 10; data[17] = 0; data[18] = 0; data[19] = 0;
        int wMinus1 = width - 1;
        int hMinus1 = height - 1;
        data[24] = (byte) (wMinus1 & 0xFF);
        data[25] = (byte) ((wMinus1 >> 8) & 0xFF);
        data[26] = (byte) ((wMinus1 >> 16) & 0xFF);
        data[27] = (byte) (hMinus1 & 0xFF);
        data[28] = (byte) ((hMinus1 >> 8) & 0xFF);
        data[29] = (byte) ((hMinus1 >> 16) & 0xFF);
        return data;
    }

    private byte[] createTestWebpVP8L(int width, int height) {
        // VP8L lossless header:
        // bytes 0..3: RIFF
        // bytes 4..7: size
        // bytes 8..11: WEBP
        // bytes 12..15: VP8L
        // bytes 16..19: chunk size
        // byte 20: 0x2F
        // bytes 21..24: 14 bits width-1, 14 bits height-1
        byte[] data = new byte[25];
        data[0] = 'R'; data[1] = 'I'; data[2] = 'F'; data[3] = 'F';
        data[8] = 'W'; data[9] = 'E'; data[10] = 'B'; data[11] = 'P';
        data[12] = 'V'; data[13] = 'P'; data[14] = '8'; data[15] = 'L';
        data[16] = 5; data[17] = 0; data[18] = 0; data[19] = 0;
        data[20] = 0x2F;
        int wMinus1 = width - 1;
        int hMinus1 = height - 1;
        data[21] = (byte) (wMinus1 & 0xFF);
        data[22] = (byte) (((wMinus1 >> 8) & 0x3F) | ((hMinus1 & 0x03) << 6));
        data[23] = (byte) ((hMinus1 >> 2) & 0xFF);
        data[24] = (byte) ((hMinus1 >> 10) & 0x0F);
        return data;
    }

    private byte[] createTestWebpVP8(int width, int height) {
        // VP8 lossy keyframe:
        // bytes 0..3: RIFF
        // bytes 4..7: size
        // bytes 8..11: WEBP
        // bytes 12..15: VP8 (with space)
        // bytes 16..19: chunk size
        // bytes 20..22: frame tag (keyframe: bit 0 is 0)
        // bytes 23..25: start code 0x9D 0x01 0x2A
        // bytes 26..27: width
        // bytes 28..29: height
        byte[] data = new byte[30];
        data[0] = 'R'; data[1] = 'I'; data[2] = 'F'; data[3] = 'F';
        data[8] = 'W'; data[9] = 'E'; data[10] = 'B'; data[11] = 'P';
        data[12] = 'V'; data[13] = 'P'; data[14] = '8'; data[15] = ' ';
        data[16] = 10; data[17] = 0; data[18] = 0; data[19] = 0;
        data[20] = 0; data[21] = 0; data[22] = 0;
        data[23] = (byte) 0x9D; data[24] = 0x01; data[25] = 0x2A;
        data[26] = (byte) (width & 0xFF);
        data[27] = (byte) ((width >> 8) & 0x3F);
        data[28] = (byte) (height & 0xFF);
        data[29] = (byte) ((height >> 8) & 0x3F);
        return data;
    }

    private byte[] createTestPdf(String body) {
        String pdf = "%PDF-1.4\n1 0 obj\n<< /Type /Catalog >>\nendobj\n"
                + body + "\nxref\n0 2\n0000000000 65535 f \n0000000009 00000 n \ntrailer\n<< /Root 1 0 R >>\n%%EOF";
        return pdf.getBytes(StandardCharsets.ISO_8859_1);
    }

    @Test
    @DisplayName("valid PNG image passes validation and decoder check")
    void validPngImageSucceeds() throws Exception {
        byte[] png = createTestImage("png", 100, 100);
        String detected = validator.validate(png, UploadPurpose.CATALOG_IMAGE, "catalog/2026/11/art.png");
        assertThat(detected).isEqualTo("image/png");
    }

    @Test
    @DisplayName("valid JPEG image passes validation and decoder check")
    void validJpegImageSucceeds() throws Exception {
        byte[] jpg = createTestImage("jpg", 50, 50);
        String detected = validator.validate(jpg, UploadPurpose.LISTING_IMAGE, "listings/2026/11/front.jpg");
        assertThat(detected).isEqualTo("image/jpeg");
    }

    @Test
    @DisplayName("valid WebP VP8X image passes validation")
    void validWebpVP8XSucceeds() {
        byte[] webp = createTestWebpVP8X(200, 150);
        String detected = validator.validate(webp, UploadPurpose.CATALOG_IMAGE, "catalog/2026/11/banner.webp");
        assertThat(detected).isEqualTo("image/webp");
    }

    @Test
    @DisplayName("valid WebP VP8L image passes validation")
    void validWebpVP8LSucceeds() {
        byte[] webp = createTestWebpVP8L(80, 60);
        String detected = validator.validate(webp, UploadPurpose.CATALOG_IMAGE, "catalog/2026/11/card.webp");
        assertThat(detected).isEqualTo("image/webp");
    }

    @Test
    @DisplayName("valid WebP VP8 image passes validation")
    void validWebpVP8Succeeds() {
        byte[] webp = createTestWebpVP8(120, 90);
        String detected = validator.validate(webp, UploadPurpose.CATALOG_IMAGE, "catalog/2026/11/photo.webp");
        assertThat(detected).isEqualTo("image/webp");
    }

    @Test
    @DisplayName("valid PDF document passes validation for PAYMENT_SLIP")
    void validPdfDocumentSucceedsForDocuments() {
        byte[] pdf = createTestPdf("");
        String detected = validator.validate(pdf, UploadPurpose.PAYMENT_SLIP, "payments/2026/11/slip.pdf");
        assertThat(detected).isEqualTo("application/pdf");
    }

    @Test
    @DisplayName("PDF document is rejected for image-only purposes like CATALOG_IMAGE")
    void pdfRejectedForImageOnlyPurpose() {
        byte[] pdf = createTestPdf("");
        assertThatThrownBy(() -> validator.validate(pdf, UploadPurpose.CATALOG_IMAGE, "catalog/2026/11/pic.pdf"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.UNSUPPORTED_FILE_TYPE);
    }

    @Test
    @DisplayName("HTML content disguised as PNG is rejected by magic bytes check")
    void htmlDisguisedAsPngIsRefused() {
        byte[] html = "<!DOCTYPE html><html><body><script>alert(1)</script></body></html>".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> validator.validate(html, UploadPurpose.CATALOG_IMAGE, "catalog/2026/11/hack.png"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.UNSUPPORTED_FILE_TYPE);
    }

    @Test
    @DisplayName("PNG magic bytes followed by garbage is rejected by PNG decoder")
    void pngWithCorruptedBodyIsRefusedByDecoder() {
        byte[] fakePng = new byte[] {
                (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n',
                'H', 'E', 'L', 'L', 'O', ' ', 'W', 'O', 'R', 'L', 'D'
        };
        assertThatThrownBy(() -> validator.validate(fakePng, UploadPurpose.CATALOG_IMAGE, "catalog/2026/11/bad.png"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.UNSUPPORTED_FILE_TYPE);
    }

    @Test
    @DisplayName("JPEG magic bytes followed by garbage is rejected by JPEG decoder")
    void jpegWithCorruptedBodyIsRefusedByDecoder() {
        byte[] fakeJpg = new byte[] {
                (byte) 0xFF, (byte) 0xD8, (byte) 0xFF,
                'N', 'O', 'T', ' ', 'J', 'P', 'E', 'G'
        };
        assertThatThrownBy(() -> validator.validate(fakeJpg, UploadPurpose.CATALOG_IMAGE, "catalog/2026/11/bad.jpg"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.UNSUPPORTED_FILE_TYPE);
    }

    @Test
    @DisplayName("PDF containing script tags is rejected to prevent HTML polyglot execution")
    void pdfWithScriptTagIsRefused() {
        byte[] evilPdf = createTestPdf("<script>alert('xss')</script>");
        assertThatThrownBy(() -> validator.validate(evilPdf, UploadPurpose.PAYMENT_SLIP, "payments/2026/11/evil.pdf"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.UNSUPPORTED_FILE_TYPE);
    }

    @Test
    @DisplayName("Mismatched extension is rejected (JPEG bytes uploaded to .png key)")
    void extensionMismatchIsRefused() throws Exception {
        byte[] jpg = createTestImage("jpg", 20, 20);
        assertThatThrownBy(() -> validator.validate(jpg, UploadPurpose.CATALOG_IMAGE, "catalog/2026/11/fake.png"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("Empty file is rejected")
    void emptyFileIsRefused() {
        assertThatThrownBy(() -> validator.validate(new byte[0], UploadPurpose.CATALOG_IMAGE, "catalog/2026/11/empty.png"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.UNSUPPORTED_FILE_TYPE);
    }
}
