package com.github.wuzhudexiaopacai.ota.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockMultipartFile;

class FirmwareStorageRouterTest {

    @TempDir
    Path tempDir;

    @Test
    void localDefault_routesToLocal() throws Exception {
        LocalFirmwareStorage local = new LocalFirmwareStorage(tempDir.toString());
        @SuppressWarnings("unchecked")
        ObjectProvider<S3FirmwareStorage> s3 = mock(ObjectProvider.class);
        FirmwareStorageRouter router = new FirmwareStorageRouter(local, s3, "local");
        MockMultipartFile file = new MockMultipartFile(
                "file", "fw.bin", "application/octet-stream",
                "abc".getBytes(StandardCharsets.UTF_8));

        FirmwareStorage.Stored stored = router.store(1L, file);

        assertEquals("LOCAL", router.type());
        try (InputStream in = router.openStream("LOCAL", stored.relativePath(), 0, 3)) {
            assertArrayEquals("abc".getBytes(StandardCharsets.UTF_8), in.readAllBytes());
        }
        assertEquals(3, router.size("LOCAL", stored.relativePath()));
        router.delete("LOCAL", stored.relativePath());
    }

    @Test
    void s3Default_routesToS3() throws Exception {
        LocalFirmwareStorage local = new LocalFirmwareStorage(tempDir.toString());
        S3FirmwareStorage s3Storage = mock(S3FirmwareStorage.class);
        when(s3Storage.isConfigured()).thenReturn(true);
        @SuppressWarnings("unchecked")
        ObjectProvider<S3FirmwareStorage> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(s3Storage);
        when(s3Storage.store(any(), any())).thenReturn(
                new FirmwareStorage.Stored("1/obj.bin", "fw.bin", 3, "a".repeat(64)));
        when(s3Storage.openStream("S3", "1/obj.bin", 1, 2)).thenReturn(
                new ByteArrayInputStream("bc".getBytes(StandardCharsets.UTF_8)));
        when(s3Storage.size("S3", "1/obj.bin")).thenReturn(3L);
        FirmwareStorageRouter router = new FirmwareStorageRouter(local, provider, "s3");

        assertEquals("S3", router.type());
        assertEquals("1/obj.bin", router.store(1L,
                new MockMultipartFile("file", "fw.bin", "application/octet-stream",
                        new byte[]{1})).relativePath());
        try (InputStream in = router.openStream("S3", "1/obj.bin", 1, 2)) {
            assertArrayEquals("bc".getBytes(StandardCharsets.UTF_8), in.readAllBytes());
        }
        assertEquals(3, router.size("S3", "1/obj.bin"));
        router.delete("S3", "1/obj.bin");
        verify(s3Storage).delete("S3", "1/obj.bin");
    }

    @Test
    void unknownStorageType_isRejected() {
        LocalFirmwareStorage local = new LocalFirmwareStorage(tempDir.toString());
        @SuppressWarnings("unchecked")
        ObjectProvider<S3FirmwareStorage> s3 = mock(ObjectProvider.class);
        FirmwareStorageRouter router = new FirmwareStorageRouter(local, s3, "local");

        assertThrows(IllegalArgumentException.class,
                () -> router.openStream("OSS", "x", 0, 1));
    }
}
