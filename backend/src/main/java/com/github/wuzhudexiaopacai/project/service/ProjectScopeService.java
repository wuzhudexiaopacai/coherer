package com.github.wuzhudexiaopacai.project.service;

import com.github.wuzhudexiaopacai.common.BizException;
import com.github.wuzhudexiaopacai.security.DataScope;
import com.github.wuzhudexiaopacai.security.DataScopeService;
import com.github.wuzhudexiaopacai.space.service.SpaceService;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 统一解析“项目 + 空间 + 用户数据范围”的交集。 */
@Service
@RequiredArgsConstructor
public class ProjectScopeService {

    private final ProjectService projectService;
    private final SpaceService spaceService;
    private final DataScopeService dataScopeService;

    /**
     * @return null 表示不限制（全平台账号且未指定项目/空间）；空集合表示无可见空间
     */
    public List<Long> resolveSpaceIds(DataScope scope, Long projectId, Long spaceId) {
        if (projectId == null) {
            return dataScopeService.resolveSpaceIds(scope, spaceId);
        }
        projectService.requireVisible(projectId, scope);
        List<Long> projectSpaces = spaceService.spaceIdsOfProject(projectId);
        if (projectSpaces.isEmpty()) {
            return List.of();
        }
        Set<Long> allowed = new HashSet<>(projectSpaces);
        if (!scope.unrestricted()) {
            allowed.retainAll(scope.spaceIds());
        }
        if (spaceId != null) {
            if (!scope.allows(spaceId) || !projectSpaces.contains(spaceId)) {
                throw new BizException(403, "空间不属于该项目或无权访问");
            }
            return spaceService.descendantIds(spaceId).stream()
                    .filter(allowed::contains)
                    .toList();
        }
        return allowed.stream().toList();
    }

    public List<Long> resolveSpaceIds(Long projectId, Long spaceId) {
        return resolveSpaceIds(dataScopeService.current(), projectId, spaceId);
    }
}
