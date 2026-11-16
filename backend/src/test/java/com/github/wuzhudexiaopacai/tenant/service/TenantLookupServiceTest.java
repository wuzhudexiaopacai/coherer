package com.github.wuzhudexiaopacai.tenant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.wuzhudexiaopacai.common.BizException;
import com.github.wuzhudexiaopacai.tenant.entity.Tenant;
import com.github.wuzhudexiaopacai.tenant.mapper.TenantMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TenantLookupServiceTest {

    private TenantMapper tenantMapper;
    private TenantLookupService service;

    @BeforeEach
    void setUp() {
        tenantMapper = mock(TenantMapper.class);
        service = new TenantLookupService(tenantMapper);
    }

    @Test
    void get_cachesLookupResult() {
        when(tenantMapper.selectById("t1")).thenReturn(tenant("t1", "ACTIVE"));

        assertThat(service.get("t1").getName()).isEqualTo("租户 t1");
        assertThat(service.get("t1").getName()).isEqualTo("租户 t1");

        verify(tenantMapper, times(1)).selectById("t1");
    }

    @Test
    void get_nullTenant_doesNotQueryDatabase() {
        assertThat(service.get(null)).isNull();

        verify(tenantMapper, times(0)).selectById(null);
    }

    @Test
    void require_missingTenant_isNotFound() {
        when(tenantMapper.selectById("ghost")).thenReturn(null);

        assertThatThrownBy(() -> service.require("ghost"))
                .isInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(404);
    }

    @Test
    void requireActive_disabledTenant_isForbidden() {
        when(tenantMapper.selectById("t1")).thenReturn(tenant("t1", "DISABLED"));

        assertThatThrownBy(() -> service.requireActive("t1"))
                .isInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(403);
        assertThat(service.isActive("t1")).isFalse();
    }

    @Test
    void defaultIds_requireConfiguredTenant() {
        Tenant tenant = tenant("t1", "ACTIVE");
        tenant.setDefaultProjectId(11L);
        tenant.setDefaultSpaceId(22L);
        when(tenantMapper.selectById("t1")).thenReturn(tenant);

        assertThat(service.defaultProjectId("t1")).isEqualTo(11L);
        assertThat(service.defaultSpaceId("t1")).isEqualTo(22L);
    }

    @Test
    void defaultProject_missingConfiguration_isServerError() {
        when(tenantMapper.selectById("t1")).thenReturn(tenant("t1", "ACTIVE"));

        assertThatThrownBy(() -> service.defaultProjectId("t1"))
                .isInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(500);
    }

    @Test
    void invalidate_forcesReload() {
        when(tenantMapper.selectById("t1")).thenReturn(tenant("t1", "ACTIVE"));
        service.get("t1");

        service.invalidate("t1");
        service.get("t1");

        verify(tenantMapper, times(2)).selectById("t1");
    }

    private Tenant tenant(String id, String status) {
        Tenant tenant = new Tenant();
        tenant.setId(id);
        tenant.setName("租户 " + id);
        tenant.setStatus(status);
        return tenant;
    }
}
