package com.github.wuzhudexiaopacai.project.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.github.wuzhudexiaopacai.common.BizException;
import com.github.wuzhudexiaopacai.device.entity.Device;
import com.github.wuzhudexiaopacai.device.mapper.DeviceMapper;
import com.github.wuzhudexiaopacai.host.entity.IotHost;
import com.github.wuzhudexiaopacai.host.mapper.IotHostMapper;
import com.github.wuzhudexiaopacai.project.dto.ProjectCreateRequest;
import com.github.wuzhudexiaopacai.project.dto.ProjectUpdateRequest;
import com.github.wuzhudexiaopacai.project.entity.Project;
import com.github.wuzhudexiaopacai.project.mapper.ProjectMapper;
import com.github.wuzhudexiaopacai.project.vo.ProjectVO;
import com.github.wuzhudexiaopacai.security.DataScope;
import com.github.wuzhudexiaopacai.space.service.SpaceService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class ProjectService extends ServiceImpl<ProjectMapper, Project> {

    public static final long DEFAULT_PROJECT_ID = 1L;

    private final SpaceService spaceService;
    private final IotHostMapper hostMapper;
    private final DeviceMapper deviceMapper;

    public List<ProjectVO> list(DataScope scope) {
        List<Project> projects = list(Wrappers.<Project>lambdaQuery()
                .orderByAsc(Project::getCreatedAt)
                .orderByAsc(Project::getId));
        if (!scope.unrestricted()) {
            Set<Long> visible = spaceService.projectIdsInSpaces(scope.spaceIds());
            projects = projects.stream().filter(project -> visible.contains(project.getId())).toList();
        }
        return projects.stream().map(this::toVO).toList();
    }

    public ProjectVO get(Long id, DataScope scope) {
        return toVO(requireVisible(id, scope));
    }

    @Transactional(rollbackFor = Exception.class)
    public ProjectVO create(ProjectCreateRequest request) {
        String code = StringUtils.hasText(request.getCode())
                ? request.getCode().trim() : "prj_" + UUID.randomUUID().toString().substring(0, 8);
        ensureCodeUnique(code, null);
        Project project = new Project();
        project.setTenantId("default");
        project.setCode(code);
        project.setName(request.getName().trim());
        project.setStatus(request.getStatus() == null
                ? "ACTIVE" : request.getStatus().toUpperCase(Locale.ROOT));
        project.setDescription(request.getDescription());
        OffsetDateTime now = OffsetDateTime.now();
        project.setCreatedAt(now);
        project.setUpdatedAt(now);
        save(project);
        return toVO(project);
    }

    @Transactional(rollbackFor = Exception.class)
    public ProjectVO update(Long id, ProjectUpdateRequest request) {
        Project project = require(id);
        if (StringUtils.hasText(request.getCode())) {
            String code = request.getCode().trim();
            ensureCodeUnique(code, id);
            project.setCode(code);
        }
        if (StringUtils.hasText(request.getName())) {
            project.setName(request.getName().trim());
        }
        if (request.getDescription() != null) {
            project.setDescription(request.getDescription());
        }
        if (StringUtils.hasText(request.getStatus())) {
            project.setStatus(request.getStatus().toUpperCase(Locale.ROOT));
        }
        project.setUpdatedAt(OffsetDateTime.now());
        updateById(project);
        return toVO(project);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        if (DEFAULT_PROJECT_ID == id) {
            throw new BizException(409, "默认项目不能删除");
        }
        require(id);
        List<Long> spaceIds = spaceService.spaceIdsOfProject(id);
        if (!spaceIds.isEmpty()) {
            throw new BizException(409, "项目下仍有空间，请先删除空间");
        }
        removeById(id);
    }

    public Project require(Long id) {
        Project project = getById(id);
        if (project == null) {
            throw new BizException(404, "项目不存在");
        }
        return project;
    }

    public Project requireVisible(Long id, DataScope scope) {
        Project project = require(id);
        if (!scope.unrestricted()
                && !spaceService.projectIdsInSpaces(scope.spaceIds()).contains(id)) {
            throw new BizException(403, "无权访问该项目");
        }
        return project;
    }

    public Project requireActive(Long id) {
        Project project = require(id);
        if ("DISABLED".equals(project.getStatus())) {
            throw new BizException(409, "项目已禁用");
        }
        return project;
    }

    public List<Long> projectSpaceIds(Long projectId) {
        return spaceService.spaceIdsOfProject(projectId);
    }

    private void ensureCodeUnique(String code, Long excludeId) {
        Long count = baseMapper.selectCount(Wrappers.<Project>lambdaQuery()
                .eq(Project::getCode, code)
                .ne(excludeId != null, Project::getId, excludeId));
        if (count != null && count > 0) {
            throw new BizException(409, "项目编码已存在");
        }
    }

    private ProjectVO toVO(Project project) {
        ProjectVO vo = new ProjectVO();
        BeanUtils.copyProperties(project, vo);
        List<Long> spaceIds = spaceService.spaceIdsOfProject(project.getId());
        vo.setSpaceCount((long) spaceIds.size());
        if (spaceIds.isEmpty()) {
            vo.setHostCount(0L);
            vo.setDeviceCount(0L);
        } else {
            vo.setHostCount(hostMapper.selectCount(Wrappers.<IotHost>lambdaQuery()
                    .in(IotHost::getSpaceId, spaceIds)));
            vo.setDeviceCount(deviceMapper.selectCount(Wrappers.<Device>lambdaQuery()
                    .in(Device::getSpaceId, spaceIds)));
        }
        return vo;
    }
}
