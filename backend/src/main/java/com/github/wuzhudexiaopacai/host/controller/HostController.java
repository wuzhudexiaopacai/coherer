package com.github.wuzhudexiaopacai.host.controller;

import com.github.wuzhudexiaopacai.audit.AuditLogService;
import com.github.wuzhudexiaopacai.common.ApiResponse;
import com.github.wuzhudexiaopacai.common.PageParams;
import com.github.wuzhudexiaopacai.common.PageResult;
import com.github.wuzhudexiaopacai.device.vo.DeviceVO;
import com.github.wuzhudexiaopacai.host.dto.HostRegisterRequest;
import com.github.wuzhudexiaopacai.host.dto.HostUpdateRequest;
import com.github.wuzhudexiaopacai.host.service.HostService;
import com.github.wuzhudexiaopacai.host.vo.HostRegisterResult;
import com.github.wuzhudexiaopacai.host.vo.HostVO;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/hosts")
@RequiredArgsConstructor
public class HostController {

    private final HostService hostService;
    private final DataScopeService dataScopeService;
    private final AuditLogService auditLogService;

    @GetMapping
    public ApiResponse<PageResult<HostVO>> page(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long spaceId) {
        return ApiResponse.ok(hostService.page(
                PageParams.current(current), PageParams.size(size, 100), keyword,
                dataScopeService.resolveSpaceIds(spaceId)));
    }

    @PostMapping
    public ApiResponse<HostRegisterResult> register(
            @Valid @RequestBody HostRegisterRequest request) {
        HostRegisterResult result = hostService.register(request, dataScopeService.current());
        auditLogService.record("HOST_REGISTER", "host", Map.of(
                "hostId", result.getHostId(),
                "name", result.getName()));
        return ApiResponse.ok(result);
    }

    @GetMapping("/{id}")
    public ApiResponse<HostVO> get(@PathVariable Long id) {
        return ApiResponse.ok(hostService.get(id, dataScopeService.current()));
    }

    @PutMapping("/{id}")
    public ApiResponse<HostVO> update(@PathVariable Long id,
                                      @Valid @RequestBody HostUpdateRequest request) {
        HostVO vo = hostService.update(id, request, dataScopeService.current());
        auditLogService.record("HOST_UPDATE", "host", Map.of(
                "id", String.valueOf(id), "hostId", vo.getHostId()));
        return ApiResponse.ok(vo);
    }

    @PostMapping("/{id}/enable")
    public ApiResponse<HostVO> enable(@PathVariable Long id) {
        HostVO vo = hostService.setEnabled(id, dataScopeService.current(), true);
        auditLogService.record("HOST_ENABLE", "host", Map.of(
                "id", String.valueOf(id), "hostId", vo.getHostId()));
        return ApiResponse.ok(vo);
    }

    @PostMapping("/{id}/disable")
    public ApiResponse<HostVO> disable(@PathVariable Long id) {
        HostVO vo = hostService.setEnabled(id, dataScopeService.current(), false);
        auditLogService.record("HOST_DISABLE", "host", Map.of(
                "id", String.valueOf(id), "hostId", vo.getHostId()));
        return ApiResponse.ok(vo);
    }

    @PostMapping("/{id}/rotate-secret")
    public ApiResponse<HostRegisterResult> rotateSecret(@PathVariable Long id) {
        HostRegisterResult result = hostService.rotateSecret(id, dataScopeService.current());
        auditLogService.record("HOST_SECRET_ROTATE", "host", Map.of(
                "id", String.valueOf(id), "hostId", result.getHostId()));
        return ApiResponse.ok(result);
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        hostService.delete(id, dataScopeService.current());
        auditLogService.record("HOST_DELETE", "host", Map.of("id", String.valueOf(id)));
        return ApiResponse.ok();
    }

    @GetMapping("/{id}/devices")
    public ApiResponse<List<DeviceVO>> devices(@PathVariable Long id) {
        return ApiResponse.ok(hostService.devices(id, dataScopeService.current()));
    }

    @PostMapping("/{id}/devices/{deviceId}")
    public ApiResponse<Void> bind(@PathVariable Long id, @PathVariable String deviceId) {
        hostService.bindDevice(id, deviceId, dataScopeService.current());
        auditLogService.record("HOST_DEVICE_BIND", "host", Map.of(
                "hostId", String.valueOf(id), "deviceId", deviceId));
        return ApiResponse.ok();
    }

    @DeleteMapping("/{id}/devices/{deviceId}")
    public ApiResponse<Void> unbind(@PathVariable Long id, @PathVariable String deviceId) {
        hostService.unbindDevice(id, deviceId, dataScopeService.current());
        auditLogService.record("HOST_DEVICE_UNBIND", "host", Map.of(
                "hostId", String.valueOf(id), "deviceId", deviceId));
        return ApiResponse.ok();
    }
}
