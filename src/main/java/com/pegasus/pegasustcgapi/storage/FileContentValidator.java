package com.pegasus.pegasustcgapi.storage;

import com.pegasus.pegasustcgapi.exception.ApiException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Locale;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Validates the actual content (magic bytes and decoder validation) of uploaded files
 * before their object keys are bound to application data.
 *
 * <p>Protects against MIME-type spoofing where an attacker sends malicious content
 * (such as HTML, JavaScript, shell scripts, or polyglots) while declaring a legitimate
 * image or document Content-Type in upload metadata.
 */
@Component
public class FileContentValidator {

    private static final Logger log = LoggerFactory.getLogger(FileContentValidator.class);

    /** Limit to prevent decompression bombs (e.g. 50 megapixels ~ 7000x7000). */
    private static final long MAX_PIXEL_COUNT = 50_000_000L;

    // Magic byte signatures
    private static final byte[] PNG_SIGNATURE = new byte[] {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    };
    private static final byte[] JPEG_SIGNATURE = new byte[] {
            (byte) 0xFF, (byte) 0xD8, (byte) 0xFF
    };
    private static final byte[] RIFF_HEADER = new byte[] { 'R', 'I', 'F', 'F' };
    private static final byte[] WEBP_HEADER = new byte[] { 'W', 'E', 'B', 'P' };
    private static final byte[] PDF_HEADER = new byte[] { '%', 'P', 'D', 'F', '-' };

    // HTML / Script injection signatures to forbid in documents
    private static final String[] DANGEROUS_PATTERNS = {
            "<script", "<html", "<body", "<head", "<iframe", "<object", "<embed", "<svg", "<!doctype html"
    };

    /**
     * Inspects the bytes, verifies magic bytes and decodability, and validates that
     * the format is allowed for the given purpose and matches the object key extension.
     *
     * @param bytes     the raw object bytes
     * @param purpose   the purpose the file was uploaded for
     * @param objectKey the destination object key
     * @return the detected canonical MIME type (e.g. "image/png", "image/jpeg", "image/webp", "application/pdf")
     */
    public String validate(byte[] bytes, UploadPurpose purpose, String objectKey) {
        if (bytes == null || bytes.length == 0) {
            throw new ApiException(ErrorCode.UNSUPPORTED_FILE_TYPE, "Uploaded file is empty");
        }

        String detectedMimeType = detectMimeType(bytes);
        if (detectedMimeType == null) {
            throw new ApiException(ErrorCode.UNSUPPORTED_FILE_TYPE,
                    "Uploaded file does not match any allowed file signature (PNG, JPEG, WebP, PDF)");
        }

        if (!purpose.allows(detectedMimeType)) {
            throw new ApiException(ErrorCode.UNSUPPORTED_FILE_TYPE,
                    "Uploaded file format (" + detectedMimeType + ") is not allowed for " + purpose
                            + "; allowed types: " + purpose.allowedContentTypes());
        }

        // Verify key extension matches the detected MIME type
        String expectedExt = purpose.extensionFor(detectedMimeType);
        String actualExt = extensionOf(objectKey);
        if (actualExt == null || (!actualExt.equalsIgnoreCase(expectedExt)
                && !(expectedExt.equalsIgnoreCase("jpg") && actualExt.equalsIgnoreCase("jpeg")))) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "Object key extension (." + actualExt + ") does not match detected file format ("
                            + detectedMimeType + ", expected ." + expectedExt + ")");
        }

        // Run format-specific decoder validation
        switch (detectedMimeType) {
            case "image/png" -> validatePng(bytes);
            case "image/jpeg" -> validateJpeg(bytes);
            case "image/webp" -> validateWebp(bytes);
            case "application/pdf" -> validatePdf(bytes);
            default -> throw new ApiException(ErrorCode.UNSUPPORTED_FILE_TYPE,
                    "Unsupported file format: " + detectedMimeType);
        }

        return detectedMimeType;
    }

    /**
     * Detects MIME type by magic byte prefixes.
     */
    public String detectMimeType(byte[] bytes) {
        if (startsWith(bytes, PNG_SIGNATURE)) {
            return "image/png";
        }
        if (startsWith(bytes, JPEG_SIGNATURE)) {
            return "image/jpeg";
        }
        if (isWebpHeader(bytes)) {
            return "image/webp";
        }
        if (startsWith(bytes, PDF_HEADER)) {
            return "application/pdf";
        }
        return null;
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data == null || data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean isWebpHeader(byte[] bytes) {
        if (bytes == null || bytes.length < 12) {
            return false;
        }
        return startsWith(bytes, RIFF_HEADER)
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P';
    }

    private void validatePng(byte[] bytes) {
        validateWithImageReader("PNG", bytes);
    }

    private void validateJpeg(byte[] bytes) {
        validateWithImageReader("JPEG", bytes);
    }

    private void validateWithImageReader(String formatName, byte[] bytes) {
        Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName(formatName);
        if (!readers.hasNext()) {
            throw new ApiException(ErrorCode.STORAGE_UNAVAILABLE, "No image reader available for " + formatName);
        }
        ImageReader reader = readers.next();
        try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (iis == null) {
                throw new ApiException(ErrorCode.UNSUPPORTED_FILE_TYPE, "Cannot create image input stream for " + formatName);
            }
            reader.setInput(iis, true, true);
            int width = reader.getWidth(0);
            int height = reader.getHeight(0);
            if (width <= 0 || height <= 0) {
                throw new ApiException(ErrorCode.UNSUPPORTED_FILE_TYPE, "Invalid dimensions for " + formatName);
            }
            if ((long) width * height > MAX_PIXEL_COUNT) {
                throw new ApiException(ErrorCode.UNSUPPORTED_FILE_TYPE,
                        "Image dimensions exceed maximum pixel count limit (" + width + "x" + height + ")");
            }
            BufferedImage image = reader.read(0);
            if (image == null) {
                throw new ApiException(ErrorCode.UNSUPPORTED_FILE_TYPE, "Failed to decode " + formatName + " image data");
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Failed to decode {} image: {}", formatName, e.getMessage());
            throw new ApiException(ErrorCode.UNSUPPORTED_FILE_TYPE, "Failed to decode " + formatName + " image: " + e.getMessage());
        } finally {
            reader.dispose();
        }
    }

    private void validateWebp(byte[] bytes) {
        if (bytes.length < 16) {
            throw new ApiException(ErrorCode.UNSUPPORTED_FILE_TYPE, "WebP file too small");
        }
        String chunkHeader = new String(bytes, 12, 4, StandardCharsets.US_ASCII);
        int width;
        int height;

        try {
            switch (chunkHeader) {
                case "VP8 " -> {
                    if (bytes.length < 30) {
                        throw new ApiException(ErrorCode.UNSUPPORTED_FILE_TYPE, "Truncated VP8 WebP");
                    }
                    if ((bytes[20] & 0x01) != 0) {
                        throw new ApiException(ErrorCode.UNSUPPORTED_FILE_TYPE, "Invalid VP8 WebP frame tag");
                    }
                    if ((bytes[23] & 0xFF) != 0x9D || (bytes[24] & 0xFF) != 0x01 || (bytes[25] & 0xFF) != 0x2A) {
                        throw new ApiException(ErrorCode.UNSUPPORTED_FILE_TYPE, "Invalid VP8 start code");
                    }
                    width = (bytes[26] & 0xFF) | ((bytes[27] & 0x3F) << 8);
                    height = (bytes[28] & 0xFF) | ((bytes[29] & 0x3F) << 8);
                }
                case "VP8L" -> {
                    if (bytes.length < 25) {
                        throw new ApiException(ErrorCode.UNSUPPORTED_FILE_TYPE, "Truncated VP8L WebP");
                    }
                    if (bytes[20] != 0x2F) {
                        throw new ApiException(ErrorCode.UNSUPPORTED_FILE_TYPE, "Invalid VP8L signature");
                    }
                    int b0 = bytes[21] & 0xFF;
                    int b1 = bytes[22] & 0xFF;
                    int b2 = bytes[23] & 0xFF;
                    int b3 = bytes[24] & 0xFF;
                    width = 1 + (((b1 & 0x3F) << 8) | b0);
                    height = 1 + (((b3 & 0x0F) << 10) | (b2 << 2) | ((b1 & 0xC0) >> 6));
                }
                case "VP8X" -> {
                    if (bytes.length < 30) {
                        throw new ApiException(ErrorCode.UNSUPPORTED_FILE_TYPE, "Truncated VP8X WebP");
                    }
                    width = 1 + ((bytes[24] & 0xFF) | ((bytes[25] & 0xFF) << 8) | ((bytes[26] & 0xFF) << 16));
                    height = 1 + ((bytes[27] & 0xFF) | ((bytes[28] & 0xFF) << 8) | ((bytes[29] & 0xFF) << 16));
                }
                default -> throw new ApiException(ErrorCode.UNSUPPORTED_FILE_TYPE,
                        "Unknown or unsupported WebP chunk: " + chunkHeader);
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(ErrorCode.UNSUPPORTED_FILE_TYPE, "Corrupted WebP file: " + e.getMessage());
        }

        if (width <= 0 || height <= 0) {
            throw new ApiException(ErrorCode.UNSUPPORTED_FILE_TYPE, "Invalid WebP dimensions");
        }
        if ((long) width * height > MAX_PIXEL_COUNT) {
            throw new ApiException(ErrorCode.UNSUPPORTED_FILE_TYPE,
                    "WebP dimensions exceed maximum pixel count limit (" + width + "x" + height + ")");
        }
    }

    private void validatePdf(byte[] bytes) {
        if (bytes.length < 32) {
            throw new ApiException(ErrorCode.UNSUPPORTED_FILE_TYPE, "PDF file too small");
        }
        if (!startsWith(bytes, PDF_HEADER)) {
            throw new ApiException(ErrorCode.UNSUPPORTED_FILE_TYPE, "Invalid PDF header");
        }
        int scanStart = Math.max(0, bytes.length - 1024);
        String tail = new String(bytes, scanStart, bytes.length - scanStart, StandardCharsets.ISO_8859_1);
        if (!tail.contains("%%EOF")) {
            throw new ApiException(ErrorCode.UNSUPPORTED_FILE_TYPE, "Missing %%EOF marker in PDF");
        }

        String head = new String(bytes, 0, Math.min(bytes.length, 4096), StandardCharsets.ISO_8859_1);
        if (!head.contains("obj") && !tail.contains("xref") && !tail.contains("/XRef") && !tail.contains("/Root")) {
            throw new ApiException(ErrorCode.UNSUPPORTED_FILE_TYPE, "Invalid PDF structure");
        }

        String contentSample = new String(bytes, 0, Math.min(bytes.length, 8192), StandardCharsets.ISO_8859_1)
                .toLowerCase(Locale.ROOT);
        for (String pattern : DANGEROUS_PATTERNS) {
            if (contentSample.contains(pattern)) {
                log.warn("Blocked potentially malicious document containing dangerous tag pattern: {}", pattern);
                throw new ApiException(ErrorCode.UNSUPPORTED_FILE_TYPE,
                        "Document contains disallowed HTML/script content");
            }
        }
    }

    private static String extensionOf(String objectKey) {
        if (objectKey == null) {
            return null;
        }
        int dot = objectKey.lastIndexOf('.');
        if (dot < 0 || dot == objectKey.length() - 1) {
            return null;
        }
        return objectKey.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
