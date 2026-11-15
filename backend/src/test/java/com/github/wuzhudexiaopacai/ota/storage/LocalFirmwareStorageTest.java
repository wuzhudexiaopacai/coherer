package com.github.wuzhudexiaopacai.ota.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
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
    void store_thenOpenAndDelete() throws Exception {
        FirmwareStorage storage = new LocalFirmwareStorage(tempDir.toString());
        MockMultipartFile file = new MockMultipartFile(
                "file", "a.bin", "application/octet-stream", new byte[]{1, 2, 3});
        FirmwareStorage.Stored stored = storage.store(1L, file);

        try (InputStream in = storage.openStream("LOCAL", stored.relativePath(), 0, 3)) {
            assertArrayEquals(new byte[]{1, 2, 3}, in.readAllBytes());
        }
        assertEquals(3, storage.size("LOCAL", stored.relativePath()));
        storage.delete("LOCAL", stored.relativePath());
        assertThrows(Exception.class,
                () -> storage.openStream("LOCAL", stored.relativePath(), 0, 3));
    }

    @Test
    void openStream_range_readsOnlyRequestedBytes() throws Exception {
        FirmwareStorage storage = new LocalFirmwareStorage(tempDir.toString());
        MockMultipartFile file = new MockMultipartFile(
                "file", "range.bin", "application/octet-stream",
                "abcdef".getBytes(StandardCharsets.UTF_8));
        FirmwareStorage.Stored stored = storage.store(1L, file);

        try (InputStream in = storage.openStream("LOCAL", stored.relativePath(), 1, 3)) {
            assertArrayEquals("bcd".getBytes(StandardCharsets.UTF_8), in.readAllBytes());
        }
        assertThrows(Exception.class,
                () -> storage.openStream("LOCAL", stored.relativePath(), 5, 2));
    }

    @Test
    void openStream_pathTraversal_isRejected() {
        FirmwareStorage storage = new LocalFirmwareStorage(tempDir.toString());
        assertThrows(IllegalArgumentException.class,
                () -> storage.openStream("LOCAL", "../outside.bin", 0, 1));
    }

    @Test
    void store_emptyFile_isRejected() {
        FirmwareStorage storage = new LocalFirmwareStorage(tempDir.toString());
        MockMultipartFile file = new MockMultipartFile(
                "file", "empty.bin", "application/octet-stream", new byte[0]);
        assertThrows(IllegalArgumentException.class, () -> storage.store(1L, file));
    }
}
