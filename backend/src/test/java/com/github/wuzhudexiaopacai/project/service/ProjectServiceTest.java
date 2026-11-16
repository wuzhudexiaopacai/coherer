package com.github.wuzhudexiaopacai.project.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.github.wuzhudexiaopacai.common.BizException;
import com.github.wuzhudexiaopacai.device.entity.Device;
import com.github.wuzhudexiaopacai.device.mapper.DeviceMapper;
import com.github.wuzhudexiaopacai.host.entity.IotHost;
import com.github.wuzhudexiaopacai.host.mapper.IotHostMapper;
import com.github.wuzhudexiaopacai.project.dto.ProjectCreateRequest;
import com.github.wuzhudexiaopacai.project.entity.Project;
import com.github.wuzhudexiaopacai.project.mapper.ProjectMapper;
import com.github.wuzhudexiaopacai.security.DataScope;
import com.github.wuzhudexiaopacai.space.service.SpaceService;
import java.util.List;
import java.util.Set;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.github.wuzhudexiaopacai.security.TenantContext;
import com.github.wuzhudexiaopacai.tenant.service.TenantLookupService;
import org.junit.jupiter.api.AfterEach;
import org.springframework.test.util.ReflectionTestUtils;

class ProjectServiceTest {

    private ProjectMapper projectMapper;
    private SpaceService spaceService;
    private IotHostMapper hostMapper;
    private DeviceMapper deviceMapper;
    private ProjectService service;

    @BeforeEach
    void setUp() {
        for (Class<?> type : List.of(Project.class, IotHost.class, Device.class)) {
            TableInfoHelper.initTableInfo(
                    new MapperBuilderAssistant(new MybatisConfiguration(), ""), type);
        }
        projectMapper = mock(ProjectMapper.class);
        spaceService = mock(SpaceService.class);
        hostMapper = mock(IotHostMapper.class);
        deviceMapper = mock(DeviceMapper.class);
        TenantLookupService tenantLookup = mock(TenantLookupService.class);
        when(tenantLookup.defaultProjectId("default")).thenReturn(1L);
        TenantContext.set("default");
        service = new ProjectService(spaceService, hostMapper, deviceMapper, tenantLookup);
        ReflectionTestUtils.setField(service, "baseMapper", projectMapper);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void list_unrestricted_returnsAllWithCounts() {
        Project a = project(1L, "default", "默认项目");
        Project b = project(2L, "prj-b", "项目 B");
        when(projectMapper.selectList(any())).thenReturn(List.of(a, b));
        when(spaceService.spaceIdsOfProject(1L)).thenReturn(List.of(1L, 2L));
        when(spaceService.spaceIdsOfProject(2L)).thenReturn(List.of());
        when(hostMapper.selectCount(any())).thenReturn(1L);
        when(deviceMapper.selectCount(any())).thenReturn(3L);

        var list = service.list(DataScope.all());

        assertEquals(2, list.size());
        assertEquals(2L, list.get(0).getSpaceCount());
        assertEquals(3L, list.get(0).getDeviceCount());
        assertEquals(0L, list.get(1).getDeviceCount());
    }

    @Test
    void list_restricted_onlyVisibleProjects() {
        Project a = project(1L, "default", "默认项目");
        Project b = project(2L, "prj-b", "项目 B");
        when(projectMapper.selectList(any())).thenReturn(List.of(a, b));
        when(spaceService.projectIdsInSpaces(List.of(10L, 11L))).thenReturn(Set.of(2L));
        when(spaceService.spaceIdsOfProject(2L)).thenReturn(List.of(10L));

        var list = service.list(DataScope.of(10L, List.of(10L, 11L)));

        assertEquals(1, list.size());
        assertEquals(2L, list.get(0).getId());
    }

    @Test
    void create_generatesCodeAndPersists() {
        when(projectMapper.selectCount(any())).thenReturn(0L);
        when(projectMapper.insert(any(Project.class))).thenAnswer(invocation -> {
            ((Project) invocation.getArgument(0)).setId(9L);
            return 1;
        });
        ProjectCreateRequest request = new ProjectCreateRequest();
        request.setName("新项目");

        var vo = service.create(request);

        assertEquals(9L, vo.getId());
        assertEquals("ACTIVE", vo.getStatus());
        assertEquals(true, vo.getCode().startsWith("prj_"));
    }

    @Test
    void create_duplicateCode_isRejected() {
        when(projectMapper.selectCount(any())).thenReturn(1L);
        ProjectCreateRequest request = new ProjectCreateRequest();
        request.setName("新项目");
        request.setCode("dup");

        BizException exception = assertThrows(BizException.class,
                () -> service.create(request));
        assertEquals(409, exception.getCode());
    }

    @Test
    void delete_defaultProject_isRejected() {
        BizException exception = assertThrows(BizException.class, () -> service.delete(1L));
        assertEquals(409, exception.getCode());
    }

    @Test
    void delete_withSpaces_isRejected() {
        when(projectMapper.selectById(2L)).thenReturn(project(2L, "prj-b", "项目 B"));
        when(spaceService.spaceIdsOfProject(2L)).thenReturn(List.of(10L));

        BizException exception = assertThrows(BizException.class, () -> service.delete(2L));
        assertEquals(409, exception.getCode());
    }

    @Test
    void delete_emptyProject_removes() {
        when(projectMapper.selectById(2L)).thenReturn(project(2L, "prj-b", "项目 B"));
        when(spaceService.spaceIdsOfProject(2L)).thenReturn(List.of());
        when(projectMapper.deleteById(2L)).thenReturn(1);

        service.delete(2L);

        verify(projectMapper).deleteById(2L);
    }

    @Test
    void get_invisibleProject_isRejected() {
        when(projectMapper.selectById(2L)).thenReturn(project(2L, "prj-b", "项目 B"));
        when(spaceService.projectIdsInSpaces(List.of(10L))).thenReturn(Set.of(1L));

        BizException exception = assertThrows(BizException.class,
                () -> service.get(2L, DataScope.of(10L, List.of(10L))));
        assertEquals(403, exception.getCode());
    }

    private Project project(Long id, String code, String name) {
        Project project = new Project();
        project.setId(id);
        project.setCode(code);
        project.setName(name);
        project.setStatus("ACTIVE");
        return project;
    }
}
