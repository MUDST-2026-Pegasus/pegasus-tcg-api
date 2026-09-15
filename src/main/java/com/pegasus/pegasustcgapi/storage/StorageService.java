package com.pegasus.pegasustcgapi.storage;

import com.pegasus.pegasustcgapi.exception.ApiException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;
import io.minio.http.Method;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * The one place that talks to object storage. Everything else — catalogue images,
 * listing photos, payment slips, return evidence — goes through here, so there is
 * a single bucket, a single key layout, and a single set of credentials.
 *
 * <p>Files never pass through this service. It hands out presigned URLs and the
 * browser transfers the bytes itself, which keeps a 5MB photo out of the API's
 * heap and off its bandwidth.
 */
@Service
public class StorageService {

    private static final Logger log = LoggerFactory.getLogger(StorageService.class);

    /** What MinIO calls a key that is not there. */
    private static final String NO_SUCH_KEY = "NoSuchKey";

    private final MinioClient client;
    private final MinioClient presigner;
    private final StorageProperties properties;

    public StorageService(
            @Qualifier("minioClient") MinioClient minioClient,
            @Qualifier("presignedUrlClient") MinioClient presignedUrlClient,
            StorageProperties properties) {

        this.client = minioClient;
        this.presigner = presignedUrlClient;
        this.properties = properties;
    }

    /**
     * A URL the browser may PUT one file to, and nothing else: it names one key,
     * one bucket, and expires.
     *
     * <p>Note what it does not do. MinIO signs the key, not the bytes, so a client
     * can send a different size or type than it declared — which is why the
     * feature calls {@link #requireUploadedFor} before saving the key.
     */
    public String presignUpload(String objectKey) {
        return presign(Method.PUT, objectKey, properties.uploadUrlTtl());
    }

    /** How long {@link #presignUpload} stays valid, for telling the client. */
    public long uploadUrlTtlSeconds() {
        return properties.uploadUrlTtl().toSeconds();
    }

    /** A short-lived read URL, so the bucket itself can stay private. */
    public String presignDownload(String objectKey) {
        return presign(Method.GET, objectKey, properties.downloadUrlTtl());
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
     * finished upload for {@code purpose}, and fit it.
     *
     * <p>The key has to carry the purpose's prefix, because every purpose shares one
     * bucket and a buyer's payment slip could otherwise be attached as public card
     * art. Then the object has to be there, and within the size and types the
     * purpose allows: the URL was issued for what the client declared, but the
     * upload itself could have sent anything.
     *
     * <p>The size is what storage measured. The type is the one the client sent
     * with the upload, so this catches mistakes and oversized files, not a file
     * deliberately labelled as something it is not.
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
        if (!purpose.allows(object.contentType())) {
            throw new ApiException(ErrorCode.UNSUPPORTED_FILE_TYPE,
                    "The upload was sent as " + object.contentType() + ", which " + purpose
                            + " does not allow; use one of " + purpose.allowedContentTypes());
        }
        return object;
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

    private String presign(Method method, String objectKey, Duration ttl) {
        try {
            return presigner.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(method)
                    .bucket(properties.bucket())
                    .object(objectKey)
                    .expiry((int) ttl.toSeconds(), TimeUnit.SECONDS)
                    .build());
        } catch (Exception e) {
            throw storageFailure("sign " + method + " for", objectKey, e);
        }
    }

    /** The cause is logged rather than returned: it carries the bucket and credentials setup. */
    private ApiException storageFailure(String action, String objectKey, Exception cause) {
        log.error("Object storage failed to {} {} in bucket {}", action, objectKey, properties.bucket(), cause);
        return new ApiException(ErrorCode.STORAGE_UNAVAILABLE);
    }
}
