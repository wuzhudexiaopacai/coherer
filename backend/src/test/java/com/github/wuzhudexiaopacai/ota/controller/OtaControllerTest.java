package com.github.wuzhudexiaopacai.ota.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.wuzhudexiaopacai.audit.AuditLogService;
import com.github.wuzhudexiaopacai.common.BizException;
import com.github.wuzhudexiaopacai.common.GlobalExceptionHandler;
import com.github.wuzhudexiaopacai.ota.entity.Firmware;
import com.github.wuzhudexiaopacai.ota.service.FirmwareService;
import com.github.wuzhudexiaopacai.ota.service.OtaService;
import com.github.wuzhudexiaopacai.ota.vo.FirmwareVO;
import com.github.wuzhudexiaopacai.ota.vo.OtaTaskVO;
import com.github.wuzhudexiaopacai.security.DataScope;
import com.github.wuzhudexiaopacai.security.DataScopeService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class OtaControllerTest {

    private FirmwareService firmwareService;
    private OtaService otaService;
    private DataScopeService dataScopeService;
    private MockMvc firmwareMvc;
    private MockMvc taskMvc;
    private MockMvc downloadMvc;
    private UsernamePasswordAuthenticationToken auth;

    @BeforeEach
    void setUp() {
        firmwareService = mock(FirmwareService.class);
        otaService = mock(OtaService.class);
        dataScopeService = mock(DataScopeService.class);
        AuditLogService auditLogService = mock(AuditLogService.class);
        when(dataScopeService.current()).thenReturn(DataScope.all());
        auth = new UsernamePasswordAuthenticationToken("admin", "n/a", List.of());
        firmwareMvc = MockMvcBuilders.standaloneSetup(
                        new FirmwareController(firmwareService, dataScopeService, auditLogService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        taskMvc = MockMvcBuilders.standaloneSetup(
                        new OtaTaskController(otaService, dataScopeService, auditLogService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        downloadMvc = MockMvcBuilders.standaloneSetup(
                        new OtaDownloadController(firmwareService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void upload_returnsFirmware() throws Exception {
        FirmwareVO vo = new FirmwareVO();
        vo.setId(10L);
        vo.setVersion("1.0.0");
        vo.setFileName("fw.bin");
        vo.setSha256("a".repeat(64));
        when(firmwareService.upload(eq(1L), eq("1.0.0"), any(), eq("admin"))).thenReturn(vo);
        MockMultipartFile file = new MockMultipartFile("file", "fw.bin",
                MediaType.APPLICATION_OCTET_STREAM_VALUE, new byte[]{1, 2, 3});

        firmwareMvc.perform(multipart("/api/ota/firmwares")
                        .file(file)
                        .param("productId", "1")
                        .param("version", "1.0.0")
                        .principal(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value("10"))
                .andExpect(jsonPath("$.data.version").value("1.0.0"));
    }

    @Test
    void list_returnsVersions() throws Exception {
        FirmwareVO vo = new FirmwareVO();
        vo.setId(10L);
        vo.setVersion("1.0.0");
        when(firmwareService.listByProduct(1L)).thenReturn(List.of(vo));

        firmwareMvc.perform(get("/api/ota/firmwares").param("productId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].version").value("1.0.0"));
    }

    @Test
    void delete_returnsOk() throws Exception {
        firmwareMvc.perform(delete("/api/ota/firmwares/10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void start_returnsTask() throws Exception {
        OtaTaskVO vo = new OtaTaskVO();
        vo.setId(88L);
        vo.setDeviceId("dev-1");
        vo.setFirmwareId(10L);
        vo.setStatus("SENT");
        when(otaService.start(any(), any(DataScope.class), eq("admin"))).thenReturn(vo);

        taskMvc.perform(post("/api/ota/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":\"dev-1\",\"firmwareId\":10}")
                        .principal(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("88"))
                .andExpect(jsonPath("$.data.status").value("SENT"));
    }

    @Test
    void start_missingDevice_isBadRequest() throws Exception {
        taskMvc.perform(post("/api/ota/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firmwareId\":10}")
                        .principal(auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void download_validSignature_streamsBinary() throws Exception {
        Firmware firmware = new Firmware();
        firmware.setId(10L);
        firmware.setFileName("fw.bin");
        firmware.setSizeBytes(3L);
        when(firmwareService.download(10L, "dev-1", 123L, "sig")).thenReturn(
                new FirmwareService.Download(firmware,
                        new ByteArrayResource("abc".getBytes(StandardCharsets.UTF_8))));

        downloadMvc.perform(get("/api/ota/download/10")
                        .param("deviceId", "dev-1")
                        .param("expires", "123")
                        .param("signature", "sig"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_OCTET_STREAM))
                .andExpect(content().bytes("abc".getBytes(StandardCharsets.UTF_8)))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("fw.bin")));
    }

    @Test
    void download_invalidSignature_returnsForbidden() throws Exception {
        when(firmwareService.download(10L, "dev-1", 123L, "bad"))
                .thenThrow(new BizException(403, "下载签名无效或已过期"));

        downloadMvc.perform(get("/api/ota/download/10")
                        .param("deviceId", "dev-1")
                        .param("expires", "123")
                        .param("signature", "bad"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }
}
