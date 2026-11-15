package com.github.wuzhudexiaopacai.space.controller;

import com.github.wuzhudexiaopacai.audit.AuditLogService;
import com.github.wuzhudexiaopacai.common.ApiResponse;
import com.github.wuzhudexiaopacai.security.DataScopeService;
import com.github.wuzhudexiaopacai.space.dto.SpaceRequest;
import com.github.wuzhudexiaopacai.space.dto.SpaceUpdateRequest;
import com.github.wuzhudexiaopacai.space.service.SpaceService;
import com.github.wuzhudexiaopacai.space.vo.SpaceNodeVO;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/spaces")
@RequiredArgsConstructor
public class SpaceController {

    private final SpaceService spaceService;
    private final DataScopeService dataScopeService;
    private final AuditLogService auditLogService;

    @GetMapping("/tree")
    public ApiResponse<List<SpaceNodeVO>> tree(
            @RequestParam(required = false) Long projectId) {
        return ApiResponse.ok(spaceService.tree(dataScopeService.current(), projectId));
    }

    @PostMapping
    public ApiResponse<SpaceNodeVO> create(@Valid @RequestBody SpaceRequest request) {
        var scope = dataScopeService.current();
        if (!scope.unrestricted()) {
            // 受限账号只能在可见空间下建子空间，不能创建新的根空间
            if (request.getParentId() == null) {
                throw new com.github.wuzhudexiaopacai.common.BizException(
                        403, "空间受限账号需在可见空间下创建子空间");
            }
            dataScopeService.requireSpace(scope, request.getParentId());
        }
        SpaceNodeVO vo = spaceService.create(request);
        auditLogService.record("SPACE_CREATE", "space", Map.of(
                "id", String.valueOf(vo.getId()),
                "name", String.valueOf(vo.getName())));
        return ApiResponse.ok(vo);
    }

    @PutMapping("/{id}")
    public ApiResponse<SpaceNodeVO> update(@PathVariable Long id,
                                           @Valid @RequestBody SpaceUpdateRequest request) {
        var scope = dataScopeService.current();
        dataScopeService.requireSpace(scope, id);
        if (request.getParentId() != null) {
            dataScopeService.requireSpace(scope, request.getParentId());
        }
        SpaceNodeVO vo = spaceService.update(id, request);
        auditLogService.record("SPACE_UPDATE", "space", Map.of(
                "id", String.valueOf(id),
                "name", String.valueOf(vo.getName())));
        return ApiResponse.ok(vo);
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        dataScopeService.requireSpace(dataScopeService.current(), id);
        spaceService.delete(id);
        auditLogService.record("SPACE_DELETE", "space", Map.of("id", String.valueOf(id)));
        return ApiResponse.ok();
    }
}
