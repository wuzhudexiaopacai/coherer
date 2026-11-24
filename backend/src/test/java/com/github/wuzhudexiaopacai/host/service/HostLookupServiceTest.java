package com.github.wuzhudexiaopacai.host.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.wuzhudexiaopacai.host.entity.IotHost;
import com.github.wuzhudexiaopacai.host.mapper.IotHostMapper;
import com.github.wuzhudexiaopacai.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HostLookupServiceTest {

    private IotHostMapper hostMapper;
    private HostLookupService service;

    @BeforeEach
    void setUp() {
        hostMapper = mock(IotHostMapper.class);
        service = new HostLookupService(hostMapper);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void cachedHostFromOtherTenant_isNotVisible() {
        when(hostMapper.selectOne(any())).thenReturn(host("host_1", "tenant-a"));
        TenantContext.set("tenant-a");
        assertThat(service.getByHostId("host_1")).isNotNull();

        TenantContext.set("tenant-b");
        assertThat(service.getByHostId("host_1")).isNull();
        verify(hostMapper, times(1)).selectOne(any());
    }

    @Test
    void getById_cachedHostFromOtherTenant_isNotVisible() {
        when(hostMapper.selectById(7L)).thenReturn(host("host_1", "tenant-a"));
        TenantContext.set("tenant-a");
        assertThat(service.getById(7L)).isNotNull();

        TenantContext.set("tenant-b");
        assertThat(service.getById(7L)).isNull();
        verify(hostMapper, times(1)).selectById(7L);
    }

    @Test
    void bypass_allowsCrossTenantLookup() {
        when(hostMapper.selectById(7L)).thenReturn(host("host_1", "tenant-a"));

        assertThat(TenantContext.callBypass(() -> service.getById(7L))).isNotNull();
    }

    @Test
    void noContext_failsClosed() {
        when(hostMapper.selectOne(any())).thenReturn(host("host_1", "tenant-a"));

        assertThatThrownBy(() -> service.getByHostId("host_1"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void nullId_returnsNullWithoutQuery() {
        assertThat(service.getById(null)).isNull();
        verify(hostMapper, times(0)).selectById(null);
    }

    private IotHost host(String hostId, String tenantId) {
        IotHost host = new IotHost();
        host.setId(7L);
        host.setHostId(hostId);
        host.setTenantId(tenantId);
        host.setSpaceId(10L);
        return host;
    }
}
