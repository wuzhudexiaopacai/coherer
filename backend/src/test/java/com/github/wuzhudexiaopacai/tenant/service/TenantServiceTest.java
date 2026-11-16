package com.github.wuzhudexiaopacai.tenant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.wuzhudexiaopacai.auth.entity.SysUser;
import com.github.wuzhudexiaopacai.auth.mapper.SysUserMapper;
import com.github.wuzhudexiaopacai.common.BizException;
import com.github.wuzhudexiaopacai.device.mapper.DeviceMapper;
import com.github.wuzhudexiaopacai.project.entity.Project;
import com.github.wuzhudexiaopacai.project.mapper.ProjectMapper;
import com.github.wuzhudexiaopacai.space.entity.Space;
import com.github.wuzhudexiaopacai.space.mapper.SpaceMapper;
import com.github.wuzhudexiaopacai.tenant.dto.TenantCreateRequest;
import com.github.wuzhudexiaopacai.tenant.dto.TenantUpdateRequest;
import com.github.wuzhudexiaopacai.tenant.entity.Tenant;
import com.github.wuzhudexiaopacai.tenant.mapper.TenantMapper;
import com.github.wuzhudexiaopacai.tenant.vo.TenantVO;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

class TenantServiceTest {

    private TenantMapper tenantMapper;
    private TenantLookupService lookupService;
    private ProjectMapper projectMapper;
    private SpaceMapper spaceMapper;
    private SysUserMapper userMapper;
    private DeviceMapper deviceMapper;
    private PasswordEncoder passwordEncoder;
    private TenantService service;

    @BeforeEach
    void setUp() {
        tenantMapper = mock(TenantMapper.class);
        lookupService = mock(TenantLookupService.class);
        projectMapper = mock(ProjectMapper.class);
        spaceMapper = mock(SpaceMapper.class);
        userMapper = mock(SysUserMapper.class);
        deviceMapper = mock(DeviceMapper.class);
        passwordEncoder = mock(PasswordEncoder.class);
        service = new TenantService(tenantMapper, lookupService, projectMapper, spaceMapper,
                userMapper, deviceMapper, passwordEncoder);
    }

    @Test
    void list_returnsTenantCounts() {
        when(tenantMapper.selectList(any())).thenReturn(List.of(
                tenant("t1", "ACTIVE"), tenant("t2", "DISABLED")));
        when(userMapper.selectCount(any())).thenReturn(2L);
        when(projectMapper.selectCount(any())).thenReturn(1L);
        when(deviceMapper.selectCount(any())).thenReturn(3L);

        List<TenantVO> result = service.list();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getId()).isEqualTo("t1");
        assertThat(result.get(0).getUserCount()).isEqualTo(2L);
        assertThat(result.get(0).getProjectCount()).isEqualTo(1L);
        assertThat(result.get(0).getDeviceCount()).isEqualTo(3L);
    }

    @Test
    void create_provisionsDefaultProjectSpaceAndAdmin() {
        TenantCreateRequest request = request("T1", "租户一");
        when(tenantMapper.selectById("T1")).thenReturn(null, tenant("T1", "ACTIVE"));
        when(passwordEncoder.encode("Admin@123")).thenReturn("hash");
        when(projectMapper.insert(any(Project.class))).thenAnswer(invocation -> {
            ((Project) invocation.getArgument(0)).setId(101L);
            return 1;
        });
        when(spaceMapper.insert(any(Space.class))).thenAnswer(invocation -> {
            ((Space) invocation.getArgument(0)).setId(202L);
            return 1;
        });
        when(userMapper.insert(any(SysUser.class))).thenReturn(1);

        TenantVO result = service.create(request);

        assertThat(result.getId()).isEqualTo("T1");
        ArgumentCaptor<Project> project = ArgumentCaptor.forClass(Project.class);
        verify(projectMapper).insert(project.capture());
        assertThat(project.getValue().getTenantId()).isEqualTo("T1");
        assertThat(project.getValue().getCode()).isEqualTo("default");
        assertThat(project.getValue().getName()).isEqualTo("默认项目");

        ArgumentCaptor<Space> space = ArgumentCaptor.forClass(Space.class);
        verify(spaceMapper).insert(space.capture());
        assertThat(space.getValue().getTenantId()).isEqualTo("T1");
        assertThat(space.getValue().getProjectId()).isEqualTo(101L);
        assertThat(space.getValue().getCode()).isEqualTo("default");

        ArgumentCaptor<SysUser> admin = ArgumentCaptor.forClass(SysUser.class);
        verify(userMapper).insert(admin.capture());
        assertThat(admin.getValue().getTenantId()).isEqualTo("T1");
        assertThat(admin.getValue().getUsername()).isEqualTo("tenant_admin");
        assertThat(admin.getValue().getPasswordHash()).isEqualTo("hash");
        assertThat(admin.getValue().getRole()).isEqualTo("ADMIN");
        assertThat(admin.getValue().getPlatformAdmin()).isZero();

        ArgumentCaptor<Tenant> tenant = ArgumentCaptor.forClass(Tenant.class);
        verify(tenantMapper).updateById(tenant.capture());
        assertThat(tenant.getValue().getDefaultProjectId()).isEqualTo(101L);
        assertThat(tenant.getValue().getDefaultSpaceId()).isEqualTo(202L);
        verify(lookupService).invalidate("T1");
    }

    @Test
    void create_duplicateCode_isConflict() {
        when(tenantMapper.selectById("T1")).thenReturn(tenant("T1", "ACTIVE"));

        assertThatThrownBy(() -> service.create(request("T1", "租户一")))
                .isInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(409);

        verify(tenantMapper, never()).insert(any(Tenant.class));
        verify(projectMapper, never()).insert(any(Project.class));
    }

    @Test
    void update_changesStatusAndInvalidatesCache() {
        Tenant tenant = tenant("t1", "ACTIVE");
        when(lookupService.require("t1")).thenReturn(tenant);
        when(tenantMapper.selectById("t1")).thenReturn(tenant);
        TenantUpdateRequest request = new TenantUpdateRequest();
        request.setName("新名称");
        request.setStatus("disabled");
        request.setPlan("ENTERPRISE");
        request.setDescription("停用");

        TenantVO result = service.update("t1", request);

        assertThat(result.getName()).isEqualTo("新名称");
        assertThat(result.getStatus()).isEqualTo("DISABLED");
        assertThat(result.getPlan()).isEqualTo("ENTERPRISE");
        assertThat(result.getDescription()).isEqualTo("停用");
        verify(tenantMapper).updateById(tenant);
        verify(lookupService).invalidate("t1");
    }

    @Test
    void update_defaultTenantToDisabled_isRejected() {
        Tenant tenant = tenant("default", "ACTIVE");
        when(lookupService.require("default")).thenReturn(tenant);
        TenantUpdateRequest request = new TenantUpdateRequest();
        request.setStatus("DISABLED");

        assertThatThrownBy(() -> service.update("default", request))
                .isInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(409);

        verify(tenantMapper, never()).updateById(any(Tenant.class));
    }

    private TenantCreateRequest request(String code, String name) {
        TenantCreateRequest request = new TenantCreateRequest();
        request.setCode(code);
        request.setName(name);
        request.setPlan("STANDARD");
        request.setAdminUsername("tenant_admin");
        request.setAdminPassword("Admin@123");
        request.setAdminNickname("租户管理员");
        return request;
    }

    private Tenant tenant(String id, String status) {
        Tenant tenant = new Tenant();
        tenant.setId(id);
        tenant.setName("租户 " + id);
        tenant.setStatus(status);
        tenant.setPlan("STANDARD");
        return tenant;
    }
}
