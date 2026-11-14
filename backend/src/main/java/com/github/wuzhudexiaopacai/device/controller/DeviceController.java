package com.github.wuzhudexiaopacai.device.controller;

import com.github.wuzhudexiaopacai.common.ApiResponse;
import com.github.wuzhudexiaopacai.common.PageParams;
import com.github.wuzhudexiaopacai.common.PageResult;
import com.github.wuzhudexiaopacai.audit.AuditLogService;
import com.github.wuzhudexiaopacai.device.dto.DeviceRegisterRequest;
import com.github.wuzhudexiaopacai.device.service.DeviceService;
import com.github.wuzhudexiaopacai.device.vo.DeviceRegisterResult;
import com.github.wuzhudexiaopacai.device.vo.DeviceVO;
import com.github.wuzhudexiaopacai.security.DataScope;
import com.github.wuzhudexiaopacai.security.DataScopeService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/devices")
@RequiredArgsConstructor
public class DeviceController {

    private final DeviceService deviceService;
    private final DataScopeService dataScopeService;
    private final AuditLogService auditLogService;

    @PostMapping
    public ApiResponse<List<DeviceRegisterResult>> register(
            @Valid @RequestBody DeviceRegisterRequest request) {
        List<DeviceRegisterResult> results = deviceService.register(request,
                dataScopeService.current());
        auditLogService.record("DEVICE_REGISTER", "device", Map.of(
                "productId", String.valueOf(request.getProductId()),
                "count", results.size()));
        return ApiResponse.ok(results);
    }

    @GetMapping
    public ApiResponse<PageResult<DeviceVO>> page(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) Long spaceId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(deviceService.page(
                PageParams.current(current), PageParams.size(size, 100),
                productId, status, keyword, dataScopeService.resolveSpaceIds(spaceId)));
    }

    @GetMapping("/{id}")
    public ApiResponse<DeviceVO> get(@PathVariable Long id) {
        DataScope scope = dataScopeService.current();
        return ApiResponse.ok(deviceService.get(id, scope));
    }

    @PostMapping("/{id}/enable")
    public ApiResponse<DeviceVO> enable(@PathVariable Long id) {
        DeviceVO vo = deviceService.setEnabled(id, dataScopeService.current(), true);
        auditLogService.record("DEVICE_ENABLE", "device", Map.of(
                "id", String.valueOf(id), "deviceId", vo.getDeviceId()));
        return ApiResponse.ok(vo);
    }

    @PostMapping("/{id}/disable")
    public ApiResponse<DeviceVO> disable(@PathVariable Long id) {
        DeviceVO vo = deviceService.setEnabled(id, dataScopeService.current(), false);
        auditLogService.record("DEVICE_DISABLE", "device", Map.of(
                "id", String.valueOf(id), "deviceId", vo.getDeviceId()));
        return ApiResponse.ok(vo);
    }

    /** 重置密钥：新密钥仅在本次响应返回一次。 */
    @PostMapping("/{id}/rotate-secret")
    public ApiResponse<DeviceRegisterResult> rotateSecret(@PathVariable Long id) {
        DeviceRegisterResult result = deviceService.rotateSecret(id, dataScopeService.current());
        auditLogService.record("DEVICE_SECRET_ROTATE", "device", Map.of(
                "id", String.valueOf(id), "deviceId", result.getDeviceId()));
        return ApiResponse.ok(result);
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        deviceService.delete(id, dataScopeService.current());
        auditLogService.record("DEVICE_DELETE", "device", Map.of("id", String.valueOf(id)));
        return ApiResponse.ok();
    }
}
