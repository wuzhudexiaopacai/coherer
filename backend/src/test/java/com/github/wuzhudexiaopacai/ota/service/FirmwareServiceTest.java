package com.github.wuzhudexiaopacai.ota.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.github.wuzhudexiaopacai.common.BizException;
import com.github.wuzhudexiaopacai.device.entity.Device;
import com.github.wuzhudexiaopacai.device.service.DeviceLookupService;
import com.github.wuzhudexiaopacai.ota.entity.Firmware;
import com.github.wuzhudexiaopacai.ota.entity.OtaTask;
import com.github.wuzhudexiaopacai.ota.mapper.FirmwareMapper;
import com.github.wuzhudexiaopacai.ota.mapper.OtaTaskMapper;
import com.github.wuzhudexiaopacai.ota.storage.FirmwareStorage;
import com.github.wuzhudexiaopacai.ota.vo.FirmwareVO;
import com.github.wuzhudexiaopacai.product.entity.Product;
import com.github.wuzhudexiaopacai.product.mapper.ProductMapper;
import java.nio.charset.StandardCharsets;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

class FirmwareServiceTest {

    private FirmwareMapper firmwareMapper;
    private OtaTaskMapper otaTaskMapper;
    private ProductMapper productMapper;
    private DeviceLookupService deviceLookupService;
    private FirmwareStorage storage;
    private OtaDownloadSigner signer;
    private FirmwareService service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), Firmware.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), OtaTask.class);
        firmwareMapper = mock(FirmwareMapper.class);
        otaTaskMapper = mock(OtaTaskMapper.class);
        productMapper = mock(ProductMapper.class);
        deviceLookupService = mock(DeviceLookupService.class);
        storage = mock(FirmwareStorage.class);
        signer = mock(OtaDownloadSigner.class);
        service = new FirmwareService(productMapper, deviceLookupService, storage, signer,
                otaTaskMapper);
        ReflectionTestUtils.setField(service, "baseMapper", firmwareMapper);
        ReflectionTestUtils.setField(service, "publicBaseUrl", "https://iot.example.com");
        ReflectionTestUtils.setField(service, "downloadTtlSeconds", 600L);
    }

    @Test
    void upload_validFile_savesMetadata() throws Exception {
        Product product = new Product();
        product.setId(1L);
        product.setTenantId("default");
        when(productMapper.selectById(1L)).thenReturn(product);
        when(firmwareMapper.selectCount(any())).thenReturn(0L);
        MockMultipartFile file = new MockMultipartFile("file", "fw.bin",
                "application/octet-stream", "abc".getBytes(StandardCharsets.UTF_8));
        when(storage.store(eq(1L), same(file))).thenReturn(
                new FirmwareStorage.Stored("1/uuid-fw.bin", "fw.bin", 3, "a".repeat(64)));
        when(firmwareMapper.insert(any(Firmware.class))).thenAnswer(invocation -> {
            ((Firmware) invocation.getArgument(0)).setId(9L);
            return 1;
        });

        FirmwareVO vo = service.upload(1L, "1.0.0", file, "admin");

        assertEquals(9L, vo.getId());
        assertEquals("fw.bin", vo.getFileName());
        assertEquals("a".repeat(64), vo.getSha256());
        assertEquals(3L, vo.getSizeBytes());
    }

    @Test
    void upload_invalidVersion_isRejected() {
        BizException exception = assertThrows(BizException.class,
                () -> service.upload(1L, "v1/../../etc", emptyFile(), "admin"));
        assertEquals(400, exception.getCode());
    }

    @Test
    void upload_duplicateVersion_isRejected() {
        when(productMapper.selectById(1L)).thenReturn(new Product());
        when(firmwareMapper.selectCount(any())).thenReturn(1L);

        BizException exception = assertThrows(BizException.class,
                () -> service.upload(1L, "1.0.0", emptyFile(), "admin"));
        assertEquals(409, exception.getCode());
    }

    @Test
    void upload_unknownProduct_isRejected() {
        when(productMapper.selectById(1L)).thenReturn(null);

        BizException exception = assertThrows(BizException.class,
                () -> service.upload(1L, "1.0.0", emptyFile(), "admin"));
        assertEquals(404, exception.getCode());
    }

    @Test
    void upload_metadataFailure_removesStoredFile() throws Exception {
        when(productMapper.selectById(1L)).thenReturn(new Product());
        when(firmwareMapper.selectCount(any())).thenReturn(0L);
        when(storage.store(any(), any())).thenReturn(
                new FirmwareStorage.Stored("1/uuid-fw.bin", "fw.bin", 1, "a".repeat(64)));
        when(firmwareMapper.insert(any(Firmware.class)))
                .thenThrow(new RuntimeException("db down"));

        assertThrows(RuntimeException.class,
                () -> service.upload(1L, "1.0.0", emptyFile(), "admin"));
        verify(storage).delete("1/uuid-fw.bin");
    }

    @Test
    void upload_storageFailure_isWrapped() throws Exception {
        when(productMapper.selectById(1L)).thenReturn(new Product());
        when(firmwareMapper.selectCount(any())).thenReturn(0L);
        when(storage.store(any(), any())).thenThrow(new java.io.IOException("disk full"));

        BizException exception = assertThrows(BizException.class,
                () -> service.upload(1L, "1.0.0", emptyFile(), "admin"));
        assertEquals(500, exception.getCode());
        assertTrue(exception.getMessage().contains("disk full"));
    }

    @Test
    void download_validSignature_returnsResource() throws Exception {
        Firmware firmware = firmware(10L, 1L, "1.0.0");
        when(signer.verify(eq(10L), eq("dev-1"), eq(123L), eq("sig"))).thenReturn(true);
        when(firmwareMapper.selectById(10L)).thenReturn(firmware);
        when(deviceLookupService.getByDeviceId("dev-1")).thenReturn(device(1L, "ONLINE"));
        when(storage.load("1/fw.bin")).thenReturn(
                new ByteArrayResource("abc".getBytes(StandardCharsets.UTF_8)));

        FirmwareService.Download download = service.download(10L, "dev-1", 123L, "sig");

        assertNotNull(download.resource());
        assertEquals("1.0.0", download.firmware().getVersion());
    }

    @Test
    void download_invalidSignature_isRejected() {
        when(signer.verify(any(), any(), eq(123L), any())).thenReturn(false);

        BizException exception = assertThrows(BizException.class,
                () -> service.download(10L, "dev-1", 123L, "bad"));
        assertEquals(403, exception.getCode());
    }

    @Test
    void download_productMismatch_isRejected() {
        when(signer.verify(any(), any(), anyLong(), any())).thenReturn(true);
        when(firmwareMapper.selectById(10L)).thenReturn(firmware(10L, 1L, "1.0.0"));
        when(deviceLookupService.getByDeviceId("dev-1")).thenReturn(device(2L, "ONLINE"));

        BizException exception = assertThrows(BizException.class,
                () -> service.download(10L, "dev-1", 123L, "sig"));
        assertEquals(403, exception.getCode());
    }

    @Test
    void download_disabledDevice_isRejected() {
        when(signer.verify(any(), any(), anyLong(), any())).thenReturn(true);
        when(firmwareMapper.selectById(10L)).thenReturn(firmware(10L, 1L, "1.0.0"));
        when(deviceLookupService.getByDeviceId("dev-1")).thenReturn(device(1L, "DISABLED"));

        BizException exception = assertThrows(BizException.class,
                () -> service.download(10L, "dev-1", 123L, "sig"));
        assertEquals(403, exception.getCode());
    }

    @Test
    void downloadUrl_containsDeviceAndSignature() {
        when(signer.sign(eq(10L), eq("dev-1"), anyLong())).thenReturn("sig");

        String url = service.downloadUrl(firmware(10L, 1L, "1.0.0"), "dev-1");

        assertTrue(url.startsWith("https://iot.example.com/api/ota/download/10?deviceId=dev-1"));
        assertTrue(url.contains("signature=sig"));
    }

    @Test
    void delete_withActiveTask_isRejected() {
        when(firmwareMapper.selectById(10L)).thenReturn(firmware(10L, 1L, "1.0.0"));
        when(otaTaskMapper.selectCount(any())).thenReturn(1L);

        BizException exception = assertThrows(BizException.class, () -> service.delete(10L));
        assertEquals(409, exception.getCode());
        verify(storage, never()).delete(any());
    }

    @Test
    void delete_withoutActiveTask_removesFile() {
        when(firmwareMapper.selectById(10L)).thenReturn(firmware(10L, 1L, "1.0.0"));
        when(otaTaskMapper.selectCount(any())).thenReturn(0L);

        service.delete(10L);

        verify(storage).delete("1/fw.bin");
    }

    private Firmware firmware(Long id, Long productId, String version) {
        Firmware firmware = new Firmware();
        firmware.setId(id);
        firmware.setProductId(productId);
        firmware.setVersion(version);
        firmware.setFileName("fw.bin");
        firmware.setSizeBytes(3L);
        firmware.setSha256("a".repeat(64));
        firmware.setStoragePath("1/fw.bin");
        return firmware;
    }

    private Device device(Long productId, String status) {
        Device device = new Device();
        device.setDeviceId("dev-1");
        device.setProductId(productId);
        device.setStatus(status);
        return device;
    }

    private MultipartFile emptyFile() {
        return new MockMultipartFile("file", "fw.bin", "application/octet-stream", new byte[]{1});
    }
}
