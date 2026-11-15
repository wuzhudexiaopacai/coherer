package com.github.wuzhudexiaopacai.ota.storage;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

/**
 * S3 兼容对象存储固件后端（MinIO / 云厂商 S3）。
 *
 * <p>只要配置了 access/secret 就会初始化，即使默认写后端已切回 local，
 * 历史 S3 固件仍可读取；未配置时对象为空，访问 S3 固件会给出明确错误。</p>
 */
@Component
public class S3FirmwareStorage implements FirmwareStorage {

    private static final Logger log = LoggerFactory.getLogger(S3FirmwareStorage.class);

    private final MinioClient client;
    private final String bucket;
    private final boolean required;

    public S3FirmwareStorage(
            @Value("${coherer.ota.s3.endpoint}") String endpoint,
            @Value("${coherer.ota.s3.access-key}") String accessKey,
            @Value("${coherer.ota.s3.secret-key}") String secretKey,
            @Value("${coherer.ota.s3.bucket}") String bucket,
            @Value("${coherer.ota.storage-type:local}") String defaultType) {
        boolean configured = StringUtils.hasText(accessKey) && StringUtils.hasText(secretKey);
        this.required = "s3".equalsIgnoreCase(defaultType);
        if (!configured && required) {
            throw new IllegalStateException(
                    "coherer.ota.storage-type=s3 时必须配置 S3 access-key/secret-key");
        }
        this.client = configured
                ? MinioClient.builder().endpoint(endpoint).credentials(accessKey, secretKey).build()
                : null;
        this.bucket = bucket;
    }

    public boolean isConfigured() {
        return client != null;
    }

    @PostConstruct
    void initBucket() throws Exception {
        if (client == null) {
            log.info("S3 固件后端未配置 access/secret，仅本地存储可用");
            return;
        }
        try {
            if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("S3 固件桶已创建 bucket={}", bucket);
            }
        } catch (Exception e) {
            // 多实例同时初始化时可能已被其他实例创建
            boolean exists = false;
            try {
                exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            } catch (Exception ignored) {
                // S3 暂时不可达
            }
            if (!exists) {
                if (required) {
                    throw e;
                }
                log.warn("可选 S3 固件后端初始化失败（默认仍为 local）: {}", e.getMessage());
            }
        }
    }

    @Override
    public String type() {
        return "S3";
    }

    private MinioClient requireClient() {
        if (client == null) {
            throw new IllegalStateException(
                    "固件存储在 S3，但当前实例未配置 S3 access-key/secret-key");
        }
        return client;
    }

    @Override
    public Stored store(Long productId, MultipartFile file) throws IOException {
        MinioClient minio = requireClient();
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("固件文件不能为空");
        }
        String original = file.getOriginalFilename() == null
                ? "firmware.bin" : file.getOriginalFilename();
        String safeName = original.replaceAll("[^A-Za-z0-9._-]", "_");
        if (safeName.length() > 100) {
            safeName = safeName.substring(safeName.length() - 100);
        }
        String object = productId + "/" + UUID.randomUUID().toString().replace("-", "")
                + "-" + safeName;
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
        try (InputStream input = new DigestInputStream(file.getInputStream(), digest)) {
            minio.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(object)
                    .stream(input, file.getSize(), -1)
                    .contentType(file.getContentType() == null
                            ? "application/octet-stream" : file.getContentType())
                    .build());
        } catch (Exception e) {
            throw new IOException("S3 固件上传失败: " + e.getMessage(), e);
        }
        return new Stored(object, safeName, file.getSize(),
                HexFormat.of().formatHex(digest.digest()));
    }

    @Override
    public Resource load(String storageType, String relativePath) throws Exception {
        GetObjectResponse response = requireClient().getObject(GetObjectArgs.builder()
                .bucket(bucket)
                .object(relativePath)
                .build());
        return new InputStreamResource(response);
    }

    @Override
    public void delete(String storageType, String relativePath) {
        try {
            requireClient().removeObject(RemoveObjectArgs.builder()
                    .bucket(bucket)
                    .object(relativePath)
                    .build());
        } catch (Exception e) {
            log.warn("S3 固件删除失败 object={} err={}", relativePath, e.getMessage());
        }
    }
}
