package com.github.wuzhudexiaopacai.tenant.controller;

import com.github.wuzhudexiaopacai.audit.AuditLogService;
import com.github.wuzhudexiaopacai.common.ApiResponse;
import com.github.wuzhudexiaopacai.tenant.dto.TenantCreateRequest;
import com.github.wuzhudexiaopacai.tenant.dto.TenantUpdateRequest;
import com.github.wuzhudexiaopacai.tenant.service.TenantService;
import com.github.wuzhudexiaopacai.tenant.vo.TenantVO;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tenants")
@RequiredArgsConstructor
public class TenantController {

    private final TenantService tenantService;
    private final AuditLogService auditLogService;

    @GetMapping
    public ApiResponse<List<TenantVO>> list() {
        return ApiResponse.ok(tenantService.list());
    }

    @PostMapping
    public ApiResponse<TenantVO> create(@Valid @RequestBody TenantCreateRequest request) {
        TenantVO vo = tenantService.create(request);
        auditLogService.record("TENANT_CREATE", "tenant", Map.of(
                "id", vo.getId(), "name", vo.getName()));
        return ApiResponse.ok(vo);
    }

    @PutMapping("/{id}")
    public ApiResponse<TenantVO> update(@PathVariable String id,
                                        @Valid @RequestBody TenantUpdateRequest request) {
        TenantVO vo = tenantService.update(id, request);
        auditLogService.record("TENANT_UPDATE", "tenant", Map.of(
                "id", id, "status", vo.getStatus()));
        return ApiResponse.ok(vo);
    }
}
