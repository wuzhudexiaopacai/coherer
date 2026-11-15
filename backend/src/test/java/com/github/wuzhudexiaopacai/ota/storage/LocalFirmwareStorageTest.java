package com.github.wuzhudexiaopacai.ota.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

class LocalFirmwareStorageTest {

    @TempDir
    Path tempDir;

    @Test
    void store_computesSha256AndSanitizesName() throws Exception {
        FirmwareStorage storage = new LocalFirmwareStorage(tempDir.toString());
        byte[] content = "firmware-binary".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile(
                "file", "my firmware v1.bin", "application/octet-stream", content);

        FirmwareStorage.Stored stored = storage.store(7L, file);

        assertEquals(content.length, stored.size());
        assertEquals("my_firmware_v1.bin", stored.fileName());
        assertEquals(HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(content)),
                stored.sha256());
        assertTrue(stored.relativePath().startsWith("7/"));
        assertEquals(content.length, Files.size(tempDir.resolve(stored.relativePath())));
    }

    @Test
    void store_thenLoadAndDelete() throws Exception {
        FirmwareStorage storage = new LocalFirmwareStorage(tempDir.toString());
        MockMultipartFile file = new MockMultipartFile(
                "file", "a.bin", "application/octet-stream", new byte[]{1, 2, 3});
        FirmwareStorage.Stored stored = storage.store(1L, file);

        assertTrue(storage.load("LOCAL", stored.relativePath()).exists());
        storage.delete("LOCAL", stored.relativePath());
        assertThrows(Exception.class, () -> storage.load("LOCAL", stored.relativePath()));
    }

    @Test
    void load_pathTraversal_isRejected() {
        FirmwareStorage storage = new LocalFirmwareStorage(tempDir.toString());
        assertThrows(IllegalArgumentException.class, () -> storage.load("LOCAL", "../outside.bin"));
    }

    @Test
    void store_emptyFile_isRejected() {
        FirmwareStorage storage = new LocalFirmwareStorage(tempDir.toString());
        MockMultipartFile file = new MockMultipartFile(
                "file", "empty.bin", "application/octet-stream", new byte[0]);
        assertThrows(IllegalArgumentException.class, () -> storage.store(1L, file));
    }
}
