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

/** 固件本地存储（生产可替换为 S3/MinIO，实现同样的相对路径语义）。 */
@Component
public class FirmwareStorage {

    private final Path baseDir;

    public FirmwareStorage(@Value("${coherer.ota.firmware-dir:../deploy/firmware}") String dir) {
        this.baseDir = Paths.get(dir).toAbsolutePath().normalize();
    }

    public record Stored(String relativePath, String fileName, long size, String sha256) {
    }

    public Stored store(Long productId, MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("固件文件不能为空");
        }
        String original = file.getOriginalFilename() == null ? "firmware.bin" : file.getOriginalFilename();
        String safeName = original.replaceAll("[^A-Za-z0-9._-]", "_");
        if (safeName.length() > 100) {
            safeName = safeName.substring(safeName.length() - 100);
        }
        String relative = productId + "/" + UUID.randomUUID().toString().replace("-", "") + "-" + safeName;
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

    public Resource load(String relativePath) throws IOException {
        Path path = resolve(relativePath);
        if (!Files.isRegularFile(path)) {
            throw new IOException("固件文件不存在");
        }
        return new FileSystemResource(path);
    }

    public void delete(String relativePath) {
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
