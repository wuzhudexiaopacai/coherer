package com.github.wuzhudexiaopacai.space.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.github.wuzhudexiaopacai.common.BizException;
import com.github.wuzhudexiaopacai.device.entity.Device;
import com.github.wuzhudexiaopacai.device.mapper.DeviceMapper;
import com.github.wuzhudexiaopacai.space.dto.SpaceRequest;
import com.github.wuzhudexiaopacai.space.dto.SpaceUpdateRequest;
import com.github.wuzhudexiaopacai.space.entity.Space;
import com.github.wuzhudexiaopacai.space.mapper.SpaceMapper;
import com.github.wuzhudexiaopacai.space.vo.SpaceNodeVO;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SpaceService extends ServiceImpl<SpaceMapper, Space> {

    public static final long DEFAULT_SPACE_ID = 1L;

    private final DeviceMapper deviceMapper;

    public List<SpaceNodeVO> tree() {
        List<Space> all = list(Wrappers.<Space>lambdaQuery()
                .orderByAsc(Space::getSort)
                .orderByAsc(Space::getId));
        return buildTree(all);
    }

    @Transactional(rollbackFor = Exception.class)
    public SpaceNodeVO create(SpaceRequest request) {
        if (request.getParentId() != null && getById(request.getParentId()) == null) {
            throw new BizException(404, "父空间不存在");
        }
        Space space = new Space();
        space.setTenantId("default");
        space.setParentId(request.getParentId());
        space.setName(request.getName());
        space.setCode(request.getCode());
        space.setType(request.getType() == null ? "GENERIC" : request.getType());
        space.setSort(request.getSort() == null ? 0 : request.getSort());
        space.setDescription(request.getDescription());
        space.setStatus(request.getStatus() == null ? "ACTIVE" : request.getStatus());
        save(space);
        return toVO(space);
    }

    @Transactional(rollbackFor = Exception.class)
    public SpaceNodeVO update(Long id, SpaceUpdateRequest request) {
        Space space = requireSpace(id);
        if (request.getParentId() != null && !request.getParentId().equals(space.getParentId())) {
            if (request.getParentId().equals(id)) {
                throw new BizException("空间不能作为自己的父级");
            }
            if (descendantIds(id).contains(request.getParentId())) {
                throw new BizException("不能移动到自己的子空间下");
            }
            if (getById(request.getParentId()) == null) {
                throw new BizException(404, "父空间不存在");
            }
            space.setParentId(request.getParentId());
        }
        if (request.getName() != null) {
            space.setName(request.getName());
        }
        if (request.getCode() != null) {
            space.setCode(request.getCode());
        }
        if (request.getType() != null) {
            space.setType(request.getType());
        }
        if (request.getSort() != null) {
            space.setSort(request.getSort());
        }
        if (request.getDescription() != null) {
            space.setDescription(request.getDescription());
        }
        if (request.getStatus() != null) {
            space.setStatus(request.getStatus());
        }
        updateById(space);
        return toVO(space);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        requireSpace(id);
        Long childCount = baseMapper.selectCount(
                Wrappers.<Space>lambdaQuery().eq(Space::getParentId, id));
        if (childCount != null && childCount > 0) {
            throw new BizException("空间下存在子空间，无法删除");
        }
        Long deviceCount = deviceMapper.selectCount(
                Wrappers.<Device>lambdaQuery().eq(Device::getSpaceId, id));
        if (deviceCount != null && deviceCount > 0) {
            throw new BizException("空间下存在设备，无法删除");
        }
        removeById(id);
    }

    /** 含自身的全部空间 id（用于按空间过滤设备/告警）。 */
    public List<Long> descendantIds(Long spaceId) {
        if (spaceId == null) {
            return List.of();
        }
        List<Space> all = list();
        Map<Long, List<Long>> children = new LinkedHashMap<>();
        for (Space space : all) {
            if (space.getParentId() != null) {
                children.computeIfAbsent(space.getParentId(), key -> new ArrayList<>())
                        .add(space.getId());
            }
        }
        List<Long> result = new ArrayList<>();
        Deque<Long> queue = new ArrayDeque<>();
        Set<Long> visited = new HashSet<>();
        queue.add(spaceId);
        while (!queue.isEmpty()) {
            Long current = queue.poll();
            if (!visited.add(current)) {
                continue;
            }
            result.add(current);
            for (Long child : children.getOrDefault(current, List.of())) {
                queue.add(child);
            }
        }
        return result;
    }

    /** 空间（含子空间）下所有设备的业务 deviceId。 */
    public List<String> deviceIdsInSpace(Long spaceId) {
        List<Long> spaceIds = descendantIds(spaceId);
        if (spaceIds.isEmpty()) {
            return List.of();
        }
        return deviceMapper.selectList(Wrappers.<Device>lambdaQuery()
                        .in(Device::getSpaceId, spaceIds))
                .stream()
                .map(Device::getDeviceId)
                .distinct()
                .toList();
    }

    public boolean exists(Long id) {
        return id != null && getById(id) != null;
    }

    private Space requireSpace(Long id) {
        Space space = getById(id);
        if (space == null) {
            throw new BizException(404, "空间不存在");
        }
        return space;
    }

    private List<SpaceNodeVO> buildTree(List<Space> all) {
        Map<Long, SpaceNodeVO> nodes = new LinkedHashMap<>();
        for (Space space : all) {
            SpaceNodeVO vo = toVO(space);
            nodes.put(vo.getId(), vo);
        }
        List<SpaceNodeVO> roots = new ArrayList<>();
        for (SpaceNodeVO node : nodes.values()) {
            if (node.getParentId() == null || !nodes.containsKey(node.getParentId())) {
                roots.add(node);
            } else {
                nodes.get(node.getParentId()).getChildren().add(node);
            }
        }
        Comparator<SpaceNodeVO> comparator = Comparator
                .comparing((SpaceNodeVO node) -> node.getSort() == null ? 0 : node.getSort())
                .thenComparing(SpaceNodeVO::getId);
        roots.sort(comparator);
        nodes.values().forEach(node -> node.getChildren().sort(comparator));
        return roots;
    }

    private SpaceNodeVO toVO(Space space) {
        SpaceNodeVO vo = new SpaceNodeVO();
        BeanUtils.copyProperties(space, vo, "children");
        vo.setChildren(new ArrayList<>());
        return vo;
    }
}
