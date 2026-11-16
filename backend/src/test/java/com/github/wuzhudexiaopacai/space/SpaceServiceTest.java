package com.github.wuzhudexiaopacai.space;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.github.wuzhudexiaopacai.common.BizException;
import com.github.wuzhudexiaopacai.device.entity.Device;
import com.github.wuzhudexiaopacai.device.mapper.DeviceMapper;
import com.github.wuzhudexiaopacai.host.entity.IotHost;
import com.github.wuzhudexiaopacai.host.mapper.IotHostMapper;
import com.github.wuzhudexiaopacai.project.entity.Project;
import com.github.wuzhudexiaopacai.project.mapper.ProjectMapper;
import com.github.wuzhudexiaopacai.space.dto.SpaceRequest;
import com.github.wuzhudexiaopacai.space.entity.Space;
import com.github.wuzhudexiaopacai.space.mapper.SpaceMapper;
import com.github.wuzhudexiaopacai.space.service.SpaceService;
import com.github.wuzhudexiaopacai.space.vo.SpaceNodeVO;
import java.util.HashSet;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.github.wuzhudexiaopacai.security.TenantContext;
import com.github.wuzhudexiaopacai.tenant.service.TenantLookupService;
import org.junit.jupiter.api.AfterEach;
import org.springframework.test.util.ReflectionTestUtils;

class SpaceServiceTest {

    private SpaceMapper spaceMapper;
    private DeviceMapper deviceMapper;
    private IotHostMapper hostMapper;
    private ProjectMapper projectMapper;
    private SpaceService service;

    @BeforeEach
    void setUp() {
        for (Class<?> type : List.of(Space.class, Device.class, IotHost.class, Project.class)) {
            TableInfoHelper.initTableInfo(
                    new MapperBuilderAssistant(new MybatisConfiguration(), ""), type);
        }
        spaceMapper = mock(SpaceMapper.class);
        deviceMapper = mock(DeviceMapper.class);
        hostMapper = mock(IotHostMapper.class);
        projectMapper = mock(ProjectMapper.class);
        TenantLookupService tenantLookup = mock(TenantLookupService.class);
        when(tenantLookup.defaultProjectId("default")).thenReturn(1L);
        TenantContext.set("default");
        service = new SpaceService(deviceMapper, hostMapper, projectMapper, tenantLookup);
        ReflectionTestUtils.setField(service, "baseMapper", spaceMapper);
        Project project = new Project();
        project.setId(1L);
        project.setStatus("ACTIVE");
        when(projectMapper.selectById(1L)).thenReturn(project);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void tree_buildsNestedHierarchy() {
        when(spaceMapper.selectList(any())).thenReturn(List.of(
                space(1L, null, "默认空间", 0),
                space(2L, 1L, "1 号楼", 1),
                space(3L, 2L, "3 层机房", 2),
                space(4L, null, "项目 B", 1)));

        List<SpaceNodeVO> tree = service.tree();

        assertEquals(2, tree.size());
        assertEquals("默认空间", tree.get(0).getName());
        assertEquals(1, tree.get(0).getChildren().size());
        assertEquals("1 号楼", tree.get(0).getChildren().get(0).getName());
        assertEquals("3 层机房", tree.get(0).getChildren().get(0).getChildren().get(0).getName());
    }

    @Test
    void descendantIds_includesSelfAndChildren() {
        when(spaceMapper.selectList(any())).thenReturn(List.of(
                space(1L, null, "默认空间", 0),
                space(2L, 1L, "1 号楼", 1),
                space(3L, 2L, "3 层机房", 2),
                space(4L, null, "项目 B", 1)));

        assertEquals(new HashSet<>(List.of(2L, 3L)), new HashSet<>(service.descendantIds(2L)));
        assertEquals(new HashSet<>(List.of(1L, 2L, 3L)), new HashSet<>(service.descendantIds(1L)));
    }

    @Test
    void descendantIds_isCachedAcrossCalls() {
        when(spaceMapper.selectList(any())).thenReturn(List.of(
                space(1L, null, "默认空间", 0),
                space(2L, 1L, "1 号楼", 1)));

        service.descendantIds(1L);
        service.descendantIds(1L);

        verify(spaceMapper, times(1)).selectList(any());
    }

    @Test
    void create_invalidatesTreeCache() {
        when(spaceMapper.selectList(any())).thenReturn(List.of(space(1L, null, "默认空间", 0)));
        SpaceRequest request = new SpaceRequest();
        request.setName("新空间");

        service.tree();
        service.create(request);
        service.tree();

        verify(spaceMapper, times(2)).selectList(any());
    }

    @Test
    void create_withMissingParent_throwsBizException() {
        when(spaceMapper.selectById(99L)).thenReturn(null);
        SpaceRequest request = new SpaceRequest();
        request.setParentId(99L);
        request.setName("新空间");

        assertThrows(BizException.class, () -> service.create(request));
    }

    @Test
    void delete_withChildren_rejected() {
        when(spaceMapper.selectById(1L)).thenReturn(space(1L, null, "默认空间", 0));
        when(spaceMapper.selectCount(any())).thenReturn(1L);

        assertThrows(BizException.class, () -> service.delete(1L));
    }

    @Test
    void delete_withDevices_rejected() {
        when(spaceMapper.selectById(1L)).thenReturn(space(1L, null, "默认空间", 0));
        when(spaceMapper.selectCount(any())).thenReturn(0L);
        when(deviceMapper.selectCount(any())).thenReturn(2L);

        assertThrows(BizException.class, () -> service.delete(1L));
    }

    @Test
    void delete_withHosts_rejected() {
        when(spaceMapper.selectById(1L)).thenReturn(space(1L, null, "默认空间", 0));
        when(spaceMapper.selectCount(any())).thenReturn(0L);
        when(deviceMapper.selectCount(any())).thenReturn(0L);
        when(hostMapper.selectCount(any())).thenReturn(1L);

        assertThrows(BizException.class, () -> service.delete(1L));
    }

    @Test
    void tree_filtersByProjectAndGroupsSpaces() {
        Space other = space(4L, null, "项目 B", 1);
        other.setProjectId(2L);
        when(spaceMapper.selectList(any())).thenReturn(List.of(
                space(1L, null, "项目 A 根", 0),
                space(2L, 1L, "A 楼", 1),
                other));

        List<SpaceNodeVO> tree = service.tree(null, 2L);

        assertEquals(1, tree.size());
        assertEquals("项目 B", tree.get(0).getName());
        assertEquals(2L, tree.get(0).getProjectId());
    }

    @Test
    void spaceIdsOfProject_andProjectIdsInSpaces() {
        Space other = space(4L, null, "项目 B", 1);
        other.setProjectId(2L);
        when(spaceMapper.selectList(any())).thenReturn(List.of(
                space(1L, null, "默认空间", 0),
                space(2L, 1L, "1 号楼", 1),
                other));

        assertEquals(List.of(1L, 2L), service.spaceIdsOfProject(1L));
        assertEquals(new HashSet<>(List.of(1L, 2L)),
                service.projectIdsInSpaces(List.of(1L, 2L, 4L)));
    }

    private Space space(Long id, Long parentId, String name, int sort) {
        Space space = new Space();
        space.setId(id);
        space.setProjectId(1L);
        space.setParentId(parentId);
        space.setName(name);
        space.setType("GENERIC");
        space.setSort(sort);
        space.setStatus("ACTIVE");
        return space;
    }
}
