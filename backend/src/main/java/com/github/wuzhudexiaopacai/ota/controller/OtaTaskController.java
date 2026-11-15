package com.github.wuzhudexiaopacai.ota.controller;

import com.github.wuzhudexiaopacai.audit.AuditLogService;
import com.github.wuzhudexiaopacai.common.ApiResponse;
import com.github.wuzhudexiaopacai.ota.dto.OtaStartRequest;
import com.github.wuzhudexiaopacai.ota.service.OtaService;
import com.github.wuzhudexiaopacai.ota.vo.OtaTaskVO;
import com.github.wuzhudexiaopacai.security.DataScopeService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ota/tasks")
@RequiredArgsConstructor
public class OtaTaskController {

    private final OtaService otaService;
    private final DataScopeService dataScopeService;
    private final AuditLogService auditLogService;

    @PostMapping
    public ApiResponse<OtaTaskVO> start(@Valid @RequestBody OtaStartRequest request,
                                        Authentication authentication) {
        OtaTaskVO vo = otaService.start(request, dataScopeService.current(),
                authentication.getName());
        auditLogService.record("OTA_START", "ota_task", Map.of(
                "id", String.valueOf(vo.getId()),
                "deviceId", vo.getDeviceId(),
                "firmwareId", String.valueOf(vo.getFirmwareId()),
                "status", vo.getStatus()));
        return ApiResponse.ok(vo);
    }

    @GetMapping
    public ApiResponse<List<OtaTaskVO>> recent(
            @RequestParam String deviceId,
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(otaService.recent(deviceId, limit, dataScopeService.current()));
    }

    /** 将设备回退到该任务升级前的版本，生成新的 ROLLBACK 任务。 */
    @PostMapping("/{id}/rollback")
    public ApiResponse<OtaTaskVO> rollback(@PathVariable Long id, Authentication authentication) {
        OtaTaskVO vo = otaService.rollback(id, dataScopeService.current(),
                authentication.getName());
        auditLogService.record("OTA_ROLLBACK", "ota_task", Map.of(
                "id", String.valueOf(vo.getId()),
                "rollbackOf", String.valueOf(id),
                "deviceId", vo.getDeviceId(),
                "fromVersion", String.valueOf(vo.getFromVersion()),
                "toVersion", String.valueOf(vo.getFirmwareVersion())));
        return ApiResponse.ok(vo);
    }
}
