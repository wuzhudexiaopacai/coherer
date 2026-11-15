package com.github.wuzhudexiaopacai.ota.storage;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/** 按固件 storage_type 路由到本地或 S3；默认写入配置的后端。 */
@Primary
@Component
public class FirmwareStorageRouter implements FirmwareStorage {

    private final LocalFirmwareStorage local;
    private final ObjectProvider<S3FirmwareStorage> s3;
    private final String defaultType;

    public FirmwareStorageRouter(
            LocalFirmwareStorage local,
            ObjectProvider<S3FirmwareStorage> s3,
            @Value("${coherer.ota.storage-type:local}") String defaultType) {
        this.local = local;
        this.s3 = s3;
        this.defaultType = defaultType.toUpperCase(Locale.ROOT);
        if (!"LOCAL".equals(this.defaultType) && !"S3".equals(this.defaultType)) {
            throw new IllegalStateException("coherer.ota.storage-type 仅支持 local/s3");
        }
        S3FirmwareStorage s3Storage = s3.getIfAvailable();
        if ("S3".equals(this.defaultType)
                && (s3Storage == null || !s3Storage.isConfigured())) {
            throw new IllegalStateException(
                    "coherer.ota.storage-type=s3 但 S3 access-key/secret-key 未配置");
        }
    }

    @Override
    public String type() {
        return defaultType;
    }

    @Override
    public Stored store(Long productId, MultipartFile file) throws IOException {
        return backend(defaultType).store(productId, file);
    }

    @Override
    public InputStream openStream(String storageType, String relativePath,
                                  long offset, long length) throws Exception {
        return backend(storageType).openStream(storageType, relativePath, offset, length);
    }

    @Override
    public long size(String storageType, String relativePath) throws Exception {
        return backend(storageType).size(storageType, relativePath);
    }

    @Override
    public void delete(String storageType, String relativePath) {
        backend(storageType).delete(storageType, relativePath);
    }

    private FirmwareStorage backend(String storageType) {
        String type = storageType == null ? "LOCAL" : storageType.toUpperCase(Locale.ROOT);
        if ("LOCAL".equals(type)) {
            return local;
        }
        if ("S3".equals(type)) {
            S3FirmwareStorage s3Storage = s3.getIfAvailable();
            if (s3Storage == null) {
                throw new IllegalStateException(
                        "固件存储在 S3，但当前实例未启用 S3 后端（coherer.ota.storage-type=s3）");
            }
            return s3Storage;
        }
        throw new IllegalArgumentException("未知固件存储类型: " + storageType);
    }
}
