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
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/spaces")
@RequiredArgsConstructor
public class SpaceController {

    private final SpaceService spaceService;
    private final DataScopeService dataScopeService;
    private final AuditLogService auditLogService;

    @GetMapping("/tree")
    public ApiResponse<List<SpaceNodeVO>> tree() {
        return ApiResponse.ok(spaceService.tree(dataScopeService.current()));
    }

    @PostMapping
    public ApiResponse<SpaceNodeVO> create(@Valid @RequestBody SpaceRequest request) {
        SpaceNodeVO vo = spaceService.create(request);
        auditLogService.record("SPACE_CREATE", "space", Map.of(
                "id", String.valueOf(vo.getId()),
                "name", String.valueOf(vo.getName())));
        return ApiResponse.ok(vo);
    }

    @PutMapping("/{id}")
    public ApiResponse<SpaceNodeVO> update(@PathVariable Long id,
                                           @Valid @RequestBody SpaceUpdateRequest request) {
        SpaceNodeVO vo = spaceService.update(id, request);
        auditLogService.record("SPACE_UPDATE", "space", Map.of(
                "id", String.valueOf(id),
                "name", String.valueOf(vo.getName())));
        return ApiResponse.ok(vo);
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        spaceService.delete(id);
        auditLogService.record("SPACE_DELETE", "space", Map.of("id", String.valueOf(id)));
        return ApiResponse.ok();
    }
}
