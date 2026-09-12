package com.pegasus.pegasustcgapi.storage;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Two clients, because the app and the browser reach MinIO by different names.
 * The first one talks to it; the second only ever signs URLs, and signs them for
 * the host the browser can actually open.
 */
@Configuration
public class StorageConfig {

    private static final Logger log = LoggerFactory.getLogger(StorageConfig.class);

    @Bean
    public MinioClient minioClient(StorageProperties properties) {
        return client(properties, properties.endpoint());
    }

    @Bean
    public MinioClient presignedUrlClient(StorageProperties properties, MinioClient minioClient) {
        return properties.sharesEndpoint()
                ? minioClient
                : client(properties, properties.publicEndpoint());
    }

    /**
     * Creates the bucket once at startup rather than on every upload.
     *
     * <p>A failure here is logged and not thrown: MinIO being down should stop
     * uploads, not stop the whole API from booting.
     */
    @Bean
    public ApplicationRunner storageBucketInitializer(MinioClient minioClient, StorageProperties properties) {
        return args -> {
            try {
                boolean exists = minioClient.bucketExists(
                        BucketExistsArgs.builder().bucket(properties.bucket()).build());

                if (!exists) {
                    minioClient.makeBucket(MakeBucketArgs.builder().bucket(properties.bucket()).build());
                    log.info("Created object storage bucket {}", properties.bucket());
                }
            } catch (Exception e) {
                log.warn("Object storage is unreachable at {}; uploads will fail until it is up"
                        + " (start it with: docker compose up -d minio)", properties.endpoint(), e);
            }
        };
    }

    private static MinioClient client(StorageProperties properties, String endpoint) {
        return MinioClient.builder()
                .endpoint(endpoint)
                .credentials(properties.accessKey(), properties.secretKey())
                .build();
    }
}
