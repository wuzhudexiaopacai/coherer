package com.github.wuzhudexiaopacai.tenant.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.wuzhudexiaopacai.audit.AuditLogService;
import com.github.wuzhudexiaopacai.common.GlobalExceptionHandler;
import com.github.wuzhudexiaopacai.tenant.service.TenantService;
import com.github.wuzhudexiaopacai.tenant.vo.TenantVO;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class TenantControllerTest {

    private TenantService tenantService;
    private MockMvc mvc;
    private UsernamePasswordAuthenticationToken auth;

    @BeforeEach
    void setUp() {
        tenantService = mock(TenantService.class);
        mvc = MockMvcBuilders.standaloneSetup(
                        new TenantController(tenantService, mock(AuditLogService.class)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        auth = new UsernamePasswordAuthenticationToken("admin", "n/a", List.of());
    }

    @Test
    void list_returnsTenants() throws Exception {
        when(tenantService.list()).thenReturn(List.of(tenant()));

        mvc.perform(get("/api/tenants").principal(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value("t1"))
                .andExpect(jsonPath("$.data[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.data[0].userCount").value(2));
    }

    @Test
    void create_returnsProvisionedTenant() throws Exception {
        when(tenantService.create(any())).thenReturn(tenant());

        mvc.perform(post("/api/tenants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"TEN1\",\"name\":\"租户一\","
                                + "\"adminUsername\":\"tenant_admin\","
                                + "\"adminPassword\":\"Admin@123\"}")
                        .principal(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("t1"))
                .andExpect(jsonPath("$.data.projectCount").value(1));
    }

    @Test
    void create_blankCode_isBadRequest() throws Exception {
        mvc.perform(post("/api/tenants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"租户一\",\"adminUsername\":\"tenant_admin\","
                                + "\"adminPassword\":\"Admin@123\"}")
                        .principal(auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void create_invalidAdminUsername_isBadRequest() throws Exception {
        mvc.perform(post("/api/tenants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"TEN1\",\"name\":\"租户一\","
                                + "\"adminUsername\":\"1\",\"adminPassword\":\"Admin@123\"}")
                        .principal(auth))
                .andExpect(status().isBadRequest());
    }

    @Test
    void update_invalidStatus_isBadRequest() throws Exception {
        mvc.perform(put("/api/tenants/t1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DELETED\"}")
                        .principal(auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    private TenantVO tenant() {
        TenantVO vo = new TenantVO();
        vo.setId("t1");
        vo.setName("租户一");
        vo.setStatus("ACTIVE");
        vo.setPlan("STANDARD");
        vo.setUserCount(2L);
        vo.setProjectCount(1L);
        vo.setDeviceCount(5L);
        return vo;
    }
}
