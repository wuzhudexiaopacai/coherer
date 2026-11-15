package com.github.wuzhudexiaopacai.ota.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/** 本地磁盘固件存储（默认后端）。 */
@Component
public class LocalFirmwareStorage implements FirmwareStorage {

    private final Path baseDir;

    public LocalFirmwareStorage(
            @Value("${coherer.ota.firmware-dir:../deploy/firmware}") String dir) {
        this.baseDir = Paths.get(dir).toAbsolutePath().normalize();
    }

    @Override
    public String type() {
        return "LOCAL";
    }

    @Override
    public Stored store(Long productId, MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("固件文件不能为空");
        }
        String original = file.getOriginalFilename() == null
                ? "firmware.bin" : file.getOriginalFilename();
        String safeName = original.replaceAll("[^A-Za-z0-9._-]", "_");
        if (safeName.length() > 100) {
            safeName = safeName.substring(safeName.length() - 100);
        }
        String relative = productId + "/" + UUID.randomUUID().toString().replace("-", "")
                + "-" + safeName;
        Path target = resolve(relative);
        Files.createDirectories(target.getParent());

        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
        try (InputStream input = new DigestInputStream(file.getInputStream(), digest);
             OutputStream output = Files.newOutputStream(target)) {
            input.transferTo(output);
        } catch (Exception e) {
            Files.deleteIfExists(target);
            throw e;
        }
        return new Stored(relative, safeName, Files.size(target),
                HexFormat.of().formatHex(digest.digest()));
    }

    @Override
    public Resource load(String storageType, String relativePath) throws IOException {
        Path path = resolve(relativePath);
        if (!Files.isRegularFile(path)) {
            throw new IOException("固件文件不存在");
        }
        return new FileSystemResource(path);
    }

    @Override
    public void delete(String storageType, String relativePath) {
        try {
            Files.deleteIfExists(resolve(relativePath));
        } catch (Exception ignored) {
            // 文件已丢失不影响元数据删除
        }
    }

    private Path resolve(String relativePath) {
        Path path = baseDir.resolve(relativePath).normalize();
        if (!path.startsWith(baseDir)) {
            throw new IllegalArgumentException("非法固件路径");
        }
        return path;
    }
}
