package com.github.wuzhudexiaopacai.space;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.github.wuzhudexiaopacai.common.BizException;
import com.github.wuzhudexiaopacai.device.entity.Device;
import com.github.wuzhudexiaopacai.device.mapper.DeviceMapper;
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
import org.springframework.test.util.ReflectionTestUtils;

class SpaceServiceTest {

    private SpaceMapper spaceMapper;
    private DeviceMapper deviceMapper;
    private SpaceService service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), Space.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), Device.class);
        spaceMapper = mock(SpaceMapper.class);
        deviceMapper = mock(DeviceMapper.class);
        service = new SpaceService(deviceMapper);
        ReflectionTestUtils.setField(service, "baseMapper", spaceMapper);
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

    private Space space(Long id, Long parentId, String name, int sort) {
        Space space = new Space();
        space.setId(id);
        space.setParentId(parentId);
        space.setName(name);
        space.setType("GENERIC");
        space.setSort(sort);
        space.setStatus("ACTIVE");
        return space;
    }
}
