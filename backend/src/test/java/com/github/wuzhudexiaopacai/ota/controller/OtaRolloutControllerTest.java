package com.github.wuzhudexiaopacai.ota.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.wuzhudexiaopacai.audit.AuditLogService;
import com.github.wuzhudexiaopacai.common.GlobalExceptionHandler;
import com.github.wuzhudexiaopacai.common.PageResult;
import com.github.wuzhudexiaopacai.ota.service.OtaRolloutService;
import com.github.wuzhudexiaopacai.ota.vo.OtaRolloutDetailVO;
import com.github.wuzhudexiaopacai.ota.vo.OtaRolloutVO;
import com.github.wuzhudexiaopacai.security.DataScope;
import com.github.wuzhudexiaopacai.security.DataScopeService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class OtaRolloutControllerTest {

    private OtaRolloutService rolloutService;
    private MockMvc mvc;
    private UsernamePasswordAuthenticationToken auth;

    @BeforeEach
    void setUp() {
        rolloutService = mock(OtaRolloutService.class);
        DataScopeService dataScopeService = mock(DataScopeService.class);
        AuditLogService auditLogService = mock(AuditLogService.class);
        when(dataScopeService.current()).thenReturn(DataScope.all());
        auth = new UsernamePasswordAuthenticationToken("admin", "n/a", List.of());
        mvc = MockMvcBuilders.standaloneSetup(
                        new OtaRolloutController(rolloutService, dataScopeService, auditLogService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void create_returnsRollout() throws Exception {
        OtaRolloutDetailVO vo = rollout();
        when(rolloutService.create(any(), any(DataScope.class), eq("admin"))).thenReturn(vo);

        mvc.perform(post("/api/ota/rollouts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firmwareId":10,"name":"灰度一批","targetType":"SPACE",
                                 "spaceId":1,"batchSize":10,"failureThresholdPercent":20,
                                 "autoAdvance":true}
                                """)
                        .principal(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("50"))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.totalBatches").value(3));
    }

    @Test
    void create_invalidTargetType_isBadRequest() throws Exception {
        mvc.perform(post("/api/ota/rollouts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firmwareId":10,"name":"灰度一批","targetType":"BOGUS",
                                 "batchSize":10,"failureThresholdPercent":20}
                                """)
                        .principal(auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void create_missingBatchSize_isBadRequest() throws Exception {
        mvc.perform(post("/api/ota/rollouts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firmwareId":10,"name":"灰度一批","targetType":"ALL",
                                 "failureThresholdPercent":20}
                                """)
                        .principal(auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void page_returnsRollouts() throws Exception {
        OtaRolloutVO vo = rollout();
        when(rolloutService.page(eq(1L), eq(10L), any(), any(), any(), any(DataScope.class),
                eq("admin")))
                .thenReturn(new PageResult<>(1, 1, 10, List.of(vo)));

        mvc.perform(get("/api/ota/rollouts").principal(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].name").value("灰度一批"));
    }

    @Test
    void pause_returnsPausedRollout() throws Exception {
        OtaRolloutDetailVO vo = rollout();
        vo.setStatus("PAUSED");
        vo.setPauseReason("变更窗口");
        when(rolloutService.pause(eq(50L), any(DataScope.class), eq("admin"), eq("变更窗口")))
                .thenReturn(vo);

        mvc.perform(post("/api/ota/rollouts/50/pause")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"变更窗口\"}")
                        .principal(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PAUSED"))
                .andExpect(jsonPath("$.data.pauseReason").value("变更窗口"));
    }

    private OtaRolloutDetailVO rollout() {
        OtaRolloutDetailVO vo = new OtaRolloutDetailVO();
        vo.setId(50L);
        vo.setName("灰度一批");
        vo.setTargetType("SPACE");
        vo.setStatus("PENDING");
        vo.setBatchSize(10);
        vo.setTotalCount(30);
        vo.setTotalBatches(3);
        vo.setFailureThresholdPercent(20);
        vo.setDevices(List.of());
        return vo;
    }
}
