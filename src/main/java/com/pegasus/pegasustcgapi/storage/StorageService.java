package com.pegasus.pegasustcgapi.storage;

import com.pegasus.pegasustcgapi.common.LogSanitizer;
import com.pegasus.pegasustcgapi.exception.ApiException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.Http.Method;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * The one place that talks to object storage. Everything else — catalogue images,
 * listing photos, payment slips, return evidence — goes through here, so there is
 * a single bucket, a single key layout, and a single set of credentials.
 *
 * <p>Files are uploaded directly to MinIO via presigned PUT URLs. When attaching
 * an uploaded file to business data, {@link #requireUploadedFor} inspects the actual
 * file content (magic bytes and decoder validation) to guarantee that malicious or
 * spoofed files are never persisted.
 */
@Service
public class StorageService {

    private static final Logger log = LoggerFactory.getLogger(StorageService.class);

    /** What MinIO calls a key that is not there. */
    private static final String NO_SUCH_KEY = "NoSuchKey";

    /**
     * Where the objects that public pages show live: catalogue art uploaded by an
     * admin, and the pictures the dev seed puts in. Nothing else — payment slips,
     * verification scans — is ever signed by {@link #readUrl}.
     */
    private static final List<String> PUBLIC_PREFIXES =
            List.of(UploadPurpose.CATALOG_IMAGE.prefix() + "/", "seed/");

    private final MinioClient client;
    private final MinioClient presigner;
    private final StorageProperties properties;
    private final FileContentValidator validator;

    public StorageService(
            @Qualifier("minioClient") MinioClient minioClient,
            @Qualifier("presignedUrlClient") MinioClient presignedUrlClient,
            StorageProperties properties) {
        this(minioClient, presignedUrlClient, properties, new FileContentValidator());
    }

    @Autowired
    public StorageService(
            @Qualifier("minioClient") MinioClient minioClient,
            @Qualifier("presignedUrlClient") MinioClient presignedUrlClient,
            StorageProperties properties,
            FileContentValidator validator) {

        this.client = minioClient;
        this.presigner = presignedUrlClient;
        this.properties = properties;
        this.validator = validator != null ? validator : new FileContentValidator();
    }

    /**
     * A URL the browser may PUT one file to, and nothing else: it names one key,
     * one bucket, and expires.
     *
     * <p>MinIO signs the key, not the bytes, so a client can send a different size
     * or type than it declared. The feature calls {@link #requireUploadedFor} before
     * saving the key to inspect the actual bytes and validate format integrity.
     */
    public String presignUpload(String objectKey) {
        return presign(Method.PUT, objectKey, properties.uploadUrlTtl());
    }

    /** How long {@link #presignUpload} stays valid, for telling the client. */
    public long uploadUrlTtlSeconds() {
        return properties.uploadUrlTtl().toSeconds();
    }

    /**
     * A short-lived read URL, so the bucket itself can stay private.
     *
     * <p>The exception is a key under a public prefix when the bucket is set to let
     * anyone read those ({@code pegasus.storage.public-read}). It goes out as a plain
     * URL that is the same on every call, so the browser and the CDN can cache it;
     * a signed one changes each time and is always a cache miss.
     *
     * <p>Presigned GET URLs include {@code response-content-type} query parameter matching
     * the canonical MIME type to prevent browser MIME sniffing.
     */
    public String presignDownload(String objectKey) {
        if (properties.publicRead() && isPublic(objectKey)) {
            return publicUrl(objectKey);
        }
        String mimeType = mimeTypeFor(objectKey);
        Map<String, String> queryParams = mimeType != null
                ? Map.of("response-content-type", mimeType)
                : null;
        return presign(Method.GET, objectKey, properties.downloadUrlTtl(), queryParams);
    }

    /**
     * Something a browser can load, for a public page, from a column that holds
     * either an object key or a URL typed in by an admin (game logos are both).
     *
     * <p>A full URL or a site path goes out as it is. A key is signed only when it
     * sits under a public prefix; any other key is refused with null and a warning,
     * so a mistyped or malicious key cannot turn a private file into a public link.
     */
    public String readUrl(String keyOrUrl) {
        if (keyOrUrl == null || keyOrUrl.isBlank()) {
            return null;
        }
        String value = keyOrUrl.trim();
        if (value.startsWith("http://") || value.startsWith("https://") || value.startsWith("/")) {
            return value;
        }
        if (!isPublic(value)) {
            log.warn("Not signing key='{}' for a public page: it is outside {}",
                    LogSanitizer.sanitize(value), PUBLIC_PREFIXES);
            return null;
        }
        return presignDownload(value);
    }

    /** @return empty when nothing was ever uploaded under this key. */
    public Optional<StoredObject> describe(String objectKey) {
        try {
            StatObjectResponse stat = client.statObject(StatObjectArgs.builder()
                    .bucket(properties.bucket())
                    .object(objectKey)
                    .build());

            return Optional.of(new StoredObject(objectKey, stat.size(), stat.contentType()));

        } catch (ErrorResponseException e) {
            if (NO_SUCH_KEY.equals(e.errorResponse().code())) {
                return Optional.empty();
            }
            throw storageFailure("read", objectKey, e);
        } catch (Exception e) {
            throw storageFailure("read", objectKey, e);
        }
    }

    /**
     * Confirms the upload actually happened before a key is written to a row.
     *
     * @throws NotFoundException when the client never uploaded, or uploaded elsewhere
     */
    public StoredObject requireUploaded(String objectKey) {
        return describe(objectKey).orElseThrow(() -> new NotFoundException(
                ErrorCode.FILE_NOT_FOUND, "Nothing was uploaded to " + objectKey));
    }

    /**
     * What a feature calls before writing a key to a row: the object has to be a
     * finished upload for {@code purpose}, within permitted size limits, and containing
     * valid magic bytes and decodable content matching the declared purpose and key extension.
     */
    public StoredObject requireUploadedFor(UploadPurpose purpose, String objectKey) {
        String prefix = purpose.prefix() + "/";
        if (!objectKey.startsWith(prefix)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "The key must come from a " + purpose + " upload (" + prefix + "...)");
        }

        StoredObject object = requireUploaded(objectKey);

        if (object.sizeBytes() > purpose.maxBytes()) {
            throw new ApiException(ErrorCode.FILE_TOO_LARGE,
                    "The upload is " + object.sizeBytes() + " bytes; " + purpose + " allows at most "
                            + purpose.maxBytes() / (1024 * 1024) + "MB");
        }
        if (object.contentType() == null || !purpose.allows(object.contentType())) {
            throw new ApiException(ErrorCode.UNSUPPORTED_FILE_TYPE,
                    "The upload was sent as " + object.contentType() + ", which " + purpose
                            + " does not allow; use one of " + purpose.allowedContentTypes());
        }

        // Validate actual object bytes (magic bytes and decoder validation)
        byte[] bytes = readObjectBytes(objectKey);
        String detectedContentType = validator.validate(bytes, purpose, objectKey);

        if (!isCompatibleContentType(object.contentType(), detectedContentType)) {
            throw new ApiException(ErrorCode.UNSUPPORTED_FILE_TYPE,
                    "Declared content type (" + object.contentType() + ") does not match actual file format ("
                            + detectedContentType + ")");
        }

        return new StoredObject(objectKey, object.sizeBytes(), detectedContentType);
    }

    private byte[] readObjectBytes(String objectKey) {
        try (InputStream stream = client.getObject(GetObjectArgs.builder()
                .bucket(properties.bucket())
                .object(objectKey)
                .build())) {
            return stream.readAllBytes();
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw storageFailure("read content for", objectKey, e);
        }
    }

    private static boolean isCompatibleContentType(String declared, String detected) {
        if (declared == null || detected == null) {
            return false;
        }
        String normDeclared = normaliseContentType(declared);
        String normDetected = normaliseContentType(detected);
        if (normDeclared.equalsIgnoreCase(normDetected)) {
            return true;
        }
        return ("image/jpeg".equalsIgnoreCase(normDetected) || "image/jpg".equalsIgnoreCase(normDetected))
                && ("image/jpeg".equalsIgnoreCase(normDeclared) || "image/jpg".equalsIgnoreCase(normDeclared));
    }

    private static String normaliseContentType(String contentType) {
        int separator = contentType.indexOf(';');
        String base = separator < 0 ? contentType : contentType.substring(0, separator);
        return base.trim().toLowerCase(Locale.ROOT);
    }

    private static String mimeTypeFor(String objectKey) {
        if (objectKey == null) {
            return null;
        }
        String lower = objectKey.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        if (lower.endsWith(".pdf")) {
            return "application/pdf";
        }
        return null;
    }

    /** Silent about a key that is already gone: deleting twice is not an error. */
    public void delete(String objectKey) {
        try {
            client.removeObject(RemoveObjectArgs.builder()
                    .bucket(properties.bucket())
                    .object(objectKey)
                    .build());
        } catch (Exception e) {
            throw storageFailure("delete", objectKey, e);
        }
    }

    private static boolean isPublic(String objectKey) {
        return PUBLIC_PREFIXES.stream().anyMatch(objectKey::startsWith);
    }

    /** {@code <public endpoint>/<bucket>/<key>}, each part of the key escaped on its own. */
    private String publicUrl(String objectKey) {
        String path = Arrays.stream(objectKey.split("/", -1))
                .map(part -> URLEncoder.encode(part, StandardCharsets.UTF_8).replace("+", "%20"))
                .collect(Collectors.joining("/"));
        return StringUtils.trimTrailingCharacter(properties.publicEndpoint(), '/')
                + "/" + properties.bucket() + "/" + path;
    }

    private String presign(Method method, String objectKey, Duration ttl) {
        return presign(method, objectKey, ttl, null);
    }

    private String presign(Method method, String objectKey, Duration ttl, Map<String, String> extraQueryParams) {
        try {
            GetPresignedObjectUrlArgs.Builder builder = GetPresignedObjectUrlArgs.builder()
                    .method(method)
                    .bucket(properties.bucket())
                    .object(objectKey)
                    .expiry((int) ttl.toSeconds(), TimeUnit.SECONDS);
            if (extraQueryParams != null && !extraQueryParams.isEmpty()) {
                builder.extraQueryParams(extraQueryParams);
            }
            return presigner.getPresignedObjectUrl(builder.build());
        } catch (Exception e) {
            throw storageFailure("sign " + method + " for", objectKey, e);
        }
    }

    /** The cause is logged rather than returned: it carries the bucket and credentials setup. */
    private ApiException storageFailure(String action, String objectKey, Exception cause) {
        log.error("Object storage failed: action='{}' key='{}' bucket='{}'",
                LogSanitizer.sanitize(action),
                LogSanitizer.sanitize(objectKey),
                LogSanitizer.sanitize(properties.bucket()),
                cause);
        return new ApiException(ErrorCode.STORAGE_UNAVAILABLE);
    }
}
