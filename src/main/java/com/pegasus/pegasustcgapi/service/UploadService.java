package com.pegasus.pegasustcgapi.service;

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
import java.time.LocalDate;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Hands out permission to upload one file.
 *
 * <p>Checks happen here rather than after the fact because the alternative is
 * discovering a 400MB file once it has already been transferred. What the caller
 * declares is taken on trust for the URL; the feature that later saves the key
 * confirms it against the object that actually arrived.
 */
@Service
public class UploadService {

    private static final Logger log = LoggerFactory.getLogger(UploadService.class);

    private final StorageService storage;
    private final Clock clock;

    public UploadService(StorageService storage, Clock clock) {
        this.storage = storage;
        this.clock = clock;
    }

    public PresignUploadResponse presign(AuthPrincipal caller, PresignUploadRequest request) {
        UploadPurpose purpose = request.purpose();

        requireRole(caller, purpose);
        requireAllowedType(purpose, request.contentType());
        requireWithinLimit(purpose, request.sizeBytes());

        String objectKey = newObjectKey(purpose, request.contentType());
        String uploadUrl = storage.presignUpload(objectKey);

        log.info("User {} may upload {} to {}", caller.userId(), purpose, objectKey);
        return new PresignUploadResponse(
                objectKey, uploadUrl, storage.uploadUrlTtlSeconds(), purpose.maxBytes());
    }

    /**
     * Keys are dated so a month's worth of uploads can be swept or archived
     * together, and random so one key cannot be guessed from another.
     */
    private String newObjectKey(UploadPurpose purpose, String contentType) {
        LocalDate today = LocalDate.now(clock);
        return "%s/%d/%02d/%s.%s".formatted(
                purpose.prefix(),
                today.getYear(),
                today.getMonthValue(),
                UUID.randomUUID(),
                purpose.extensionFor(contentType));
    }

    /** The coarse gate only. Owning the listing or the order is the feature's own check. */
    private static void requireRole(AuthPrincipal caller, UploadPurpose purpose) {
        RoleCode required = purpose.requiredRole();
        if (required != null && !caller.hasRole(required)) {
            throw new ForbiddenException(ErrorCode.ACCESS_DENIED,
                    purpose + " uploads are for " + required + " only");
        }
    }

    private static void requireAllowedType(UploadPurpose purpose, String contentType) {
        if (!purpose.allows(contentType)) {
            throw new ApiException(ErrorCode.UNSUPPORTED_FILE_TYPE,
                    contentType + " is not allowed for " + purpose + "; use one of "
                            + purpose.allowedContentTypes());
        }
    }

    private static void requireWithinLimit(UploadPurpose purpose, long sizeBytes) {
        if (sizeBytes > purpose.maxBytes()) {
            throw new ApiException(ErrorCode.FILE_TOO_LARGE,
                    purpose + " allows at most " + purpose.maxBytes() / (1024 * 1024) + "MB");
        }
    }
}
