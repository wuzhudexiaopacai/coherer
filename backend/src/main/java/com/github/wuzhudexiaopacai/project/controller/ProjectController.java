package com.github.wuzhudexiaopacai.project.controller;

import com.github.wuzhudexiaopacai.audit.AuditLogService;
import com.github.wuzhudexiaopacai.common.ApiResponse;
import com.github.wuzhudexiaopacai.project.dto.ProjectCreateRequest;
import com.github.wuzhudexiaopacai.project.dto.ProjectUpdateRequest;
import com.github.wuzhudexiaopacai.project.service.ProjectService;
import com.github.wuzhudexiaopacai.project.vo.ProjectVO;
import com.github.wuzhudexiaopacai.security.DataScopeService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;
    private final DataScopeService dataScopeService;
    private final AuditLogService auditLogService;

    @GetMapping
    public ApiResponse<List<ProjectVO>> list() {
        return ApiResponse.ok(projectService.list(dataScopeService.current()));
    }

    @GetMapping("/{id}")
    public ApiResponse<ProjectVO> get(@PathVariable Long id) {
        return ApiResponse.ok(projectService.get(id, dataScopeService.current()));
    }

    @PostMapping
    public ApiResponse<ProjectVO> create(@Valid @RequestBody ProjectCreateRequest request) {
        dataScopeService.requireUnrestricted("项目管理");
        ProjectVO vo = projectService.create(request);
        auditLogService.record("PROJECT_CREATE", "project", Map.of(
                "id", String.valueOf(vo.getId()), "code", vo.getCode()));
        return ApiResponse.ok(vo);
    }

    @PutMapping("/{id}")
    public ApiResponse<ProjectVO> update(@PathVariable Long id,
                                         @Valid @RequestBody ProjectUpdateRequest request) {
        dataScopeService.requireUnrestricted("项目管理");
        ProjectVO vo = projectService.update(id, request);
        auditLogService.record("PROJECT_UPDATE", "project", Map.of(
                "id", String.valueOf(id), "code", vo.getCode()));
        return ApiResponse.ok(vo);
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        dataScopeService.requireUnrestricted("项目管理");
        projectService.delete(id);
        auditLogService.record("PROJECT_DELETE", "project", Map.of("id", String.valueOf(id)));
        return ApiResponse.ok();
    }
}
