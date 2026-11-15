package com.github.wuzhudexiaopacai.space.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.wuzhudexiaopacai.common.BizException;
import com.github.wuzhudexiaopacai.device.entity.Device;
import com.github.wuzhudexiaopacai.device.mapper.DeviceMapper;
import com.github.wuzhudexiaopacai.host.entity.IotHost;
import com.github.wuzhudexiaopacai.host.mapper.IotHostMapper;
import com.github.wuzhudexiaopacai.project.entity.Project;
import com.github.wuzhudexiaopacai.project.mapper.ProjectMapper;
import com.github.wuzhudexiaopacai.project.service.ProjectService;
import com.github.wuzhudexiaopacai.security.DataScope;
import com.github.wuzhudexiaopacai.space.dto.SpaceRequest;
import com.github.wuzhudexiaopacai.space.dto.SpaceUpdateRequest;
import com.github.wuzhudexiaopacai.space.entity.Space;
import com.github.wuzhudexiaopacai.space.mapper.SpaceMapper;
import com.github.wuzhudexiaopacai.space.vo.SpaceNodeVO;
import java.time.Duration;
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
    private final IotHostMapper hostMapper;
    private final ProjectMapper projectMapper;

    /** 空间树变化不频繁：缓存整表与后代集合，写操作后主动失效。 */
    private final Cache<String, List<Space>> allSpacesCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(60))
            .maximumSize(1)
            .build();
    private final Cache<Long, List<Long>> descendantCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(60))
            .maximumSize(2_000)
            .build();
    private final Cache<Long, List<Long>> projectSpaceCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(60))
            .maximumSize(2_000)
            .build();

    private List<Space> allSpaces() {
        return allSpacesCache.get("all", ignored -> list(Wrappers.<Space>lambdaQuery()
                .orderByAsc(Space::getSort)
                .orderByAsc(Space::getId)));
    }

    private void invalidateSpaceCaches() {
        allSpacesCache.invalidateAll();
        descendantCache.invalidateAll();
        projectSpaceCache.invalidateAll();
    }

    public List<SpaceNodeVO> tree() {
        return tree(null, null);
    }

    /** 按数据范围返回空间树：受限用户只能看到自己空间及其子空间。 */
    public List<SpaceNodeVO> tree(DataScope scope) {
        return tree(scope, null);
    }

    /** 项目 + 数据范围双重过滤后的空间树。 */
    public List<SpaceNodeVO> tree(DataScope scope, Long projectId) {
        List<Space> all = allSpaces();
        if (projectId != null) {
            all = all.stream()
                    .filter(space -> projectId.equals(space.getProjectId()))
                    .toList();
        }
        if (scope != null && !scope.unrestricted()) {
            Set<Long> allowed = new HashSet<>(scope.spaceIds());
            all = all.stream().filter(space -> allowed.contains(space.getId())).toList();
        }
        return buildTree(all);
    }

    @Transactional(rollbackFor = Exception.class)
    public SpaceNodeVO create(SpaceRequest request) {
        Long projectId;
        if (request.getParentId() != null) {
            Space parent = requireSpace(request.getParentId());
            if (request.getProjectId() != null
                    && !request.getProjectId().equals(parent.getProjectId())) {
                throw new BizException(409, "子空间必须与父空间属于同一项目");
            }
            projectId = parent.getProjectId();
        } else {
            projectId = request.getProjectId() == null
                    ? ProjectService.DEFAULT_PROJECT_ID : request.getProjectId();
        }
        Project project = projectMapper.selectById(projectId);
        if (project == null) {
            throw new BizException(404, "项目不存在");
        }
        if ("DISABLED".equals(project.getStatus())) {
            throw new BizException(409, "项目已禁用，不能新建空间");
        }
        Space space = new Space();
        space.setTenantId("default");
        space.setProjectId(projectId);
        space.setParentId(request.getParentId());
        space.setName(request.getName());
        space.setCode(request.getCode());
        space.setType(request.getType() == null ? "GENERIC" : request.getType());
        space.setSort(request.getSort() == null ? 0 : request.getSort());
        space.setDescription(request.getDescription());
        space.setStatus(request.getStatus() == null ? "ACTIVE" : request.getStatus());
        save(space);
        invalidateSpaceCaches();
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
            Space newParent = requireSpace(request.getParentId());
            if (!space.getProjectId().equals(newParent.getProjectId())) {
                throw new BizException(409, "不能把空间移动到其他项目");
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
        invalidateSpaceCaches();
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
        Long hostCount = hostMapper.selectCount(
                Wrappers.<IotHost>lambdaQuery().eq(IotHost::getSpaceId, id));
        if (hostCount != null && hostCount > 0) {
            throw new BizException("空间下存在主机，无法删除");
        }
        removeById(id);
        invalidateSpaceCaches();
    }

    /** 项目下的全部空间 id（含所有根/子空间）。 */
    public List<Long> spaceIdsOfProject(Long projectId) {
        if (projectId == null) {
            return List.of();
        }
        return projectSpaceCache.get(projectId, id -> allSpaces().stream()
                .filter(space -> id.equals(space.getProjectId()))
                .map(Space::getId)
                .toList());
    }

    /** 给定空间集合覆盖到的项目 id，用于受限用户的项目列表。 */
    public Set<Long> projectIdsInSpaces(List<Long> spaceIds) {
        if (spaceIds == null || spaceIds.isEmpty()) {
            return Set.of();
        }
        Set<Long> allowed = new HashSet<>(spaceIds);
        return allSpaces().stream()
                .filter(space -> allowed.contains(space.getId()))
                .map(Space::getProjectId)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
    }

    public Long projectIdOf(Long spaceId) {
        if (spaceId == null) {
            return null;
        }
        return allSpaces().stream()
                .filter(space -> spaceId.equals(space.getId()))
                .map(Space::getProjectId)
                .findFirst()
                .orElse(null);
    }

    /** 含自身的全部空间 id（用于按空间过滤设备/告警）。 */
    public List<Long> descendantIds(Long spaceId) {
        if (spaceId == null) {
            return List.of();
        }
        return descendantCache.get(spaceId, this::computeDescendantIds);
    }

    private List<Long> computeDescendantIds(Long spaceId) {
        List<Space> all = allSpaces();
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
        return deviceIdsInSpaces(descendantIds(spaceId));
    }

    /** 给定空间集合下所有设备的业务 deviceId；null/空集合返回空列表。 */
    public List<String> deviceIdsInSpaces(List<Long> spaceIds) {
        if (spaceIds == null || spaceIds.isEmpty()) {
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
