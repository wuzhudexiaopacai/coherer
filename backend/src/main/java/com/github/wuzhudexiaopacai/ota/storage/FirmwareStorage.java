package com.github.wuzhudexiaopacai.ota.storage;

import java.io.IOException;
import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

/**
 * 固件存储后端。每个固件记录自己的 storage_type，切换默认后端后仍能读取历史文件。
 */
public interface FirmwareStorage {

    /** 当前默认写入后端：LOCAL / S3。 */
    String type();

    Stored store(Long productId, MultipartFile file) throws IOException;

    Resource load(String storageType, String relativePath) throws Exception;

    void delete(String storageType, String relativePath);

    record Stored(String relativePath, String fileName, long size, String sha256) {
    }
}
