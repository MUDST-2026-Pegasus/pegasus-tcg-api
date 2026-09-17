package com.pegasus.pegasustcgapi.storage;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Everything under {@code pegasus.storage}.
 *
 * @param endpoint        where the app reaches MinIO; inside a container this is
 *                        the compose service name, not localhost.
 * @param publicEndpoint  where the browser reaches it. A presigned URL carries the
 *                        host it was signed for, so signing with {@code endpoint}
 *                        would hand the browser a URL it cannot resolve.
 * @param uploadUrlTtl    how long a presigned PUT stays valid.
 * @param downloadUrlTtl  how long a presigned GET stays valid.
 */
@Validated
@ConfigurationProperties("pegasus.storage")
public record StorageProperties(

        @NotBlank String endpoint,
        @NotBlank String publicEndpoint,
        @NotBlank String accessKey,
        @NotBlank String secretKey,
        @NotBlank String bucket,
        @NotNull Duration uploadUrlTtl,
        @NotNull Duration downloadUrlTtl) {

    /** True when the app and the browser reach MinIO by the same name. */
    public boolean sharesEndpoint() {
        return endpoint.equals(publicEndpoint);
    }
}
