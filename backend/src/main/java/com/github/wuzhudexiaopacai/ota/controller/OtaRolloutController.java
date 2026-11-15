package com.github.wuzhudexiaopacai.ota.controller;

import com.github.wuzhudexiaopacai.audit.AuditLogService;
import com.github.wuzhudexiaopacai.common.ApiResponse;
import com.github.wuzhudexiaopacai.common.PageParams;
import com.github.wuzhudexiaopacai.common.PageResult;
import com.github.wuzhudexiaopacai.ota.dto.OtaRolloutCreateRequest;
import com.github.wuzhudexiaopacai.ota.dto.OtaRolloutPauseRequest;
import com.github.wuzhudexiaopacai.ota.service.OtaRolloutService;
import com.github.wuzhudexiaopacai.ota.vo.OtaRolloutDetailVO;
import com.github.wuzhudexiaopacai.ota.vo.OtaRolloutVO;
import com.github.wuzhudexiaopacai.security.DataScopeService;
import jakarta.validation.Valid;
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
@RequestMapping("/api/ota/rollouts")
@RequiredArgsConstructor
public class OtaRolloutController {

    private final OtaRolloutService rolloutService;
    private final DataScopeService dataScopeService;
    private final AuditLogService auditLogService;

    @PostMapping
    public ApiResponse<OtaRolloutDetailVO> create(
            @Valid @RequestBody OtaRolloutCreateRequest request, Authentication authentication) {
        String operator = authentication.getName();
        OtaRolloutDetailVO vo = rolloutService.create(request,
                dataScopeService.current(), operator);
        auditLogService.record("OTA_ROLLOUT_CREATE", "ota_rollout", Map.of(
                "id", String.valueOf(vo.getId()),
                "name", vo.getName(),
                "targetType", vo.getTargetType(),
                "total", String.valueOf(vo.getTotalCount()),
                "batchSize", String.valueOf(vo.getBatchSize())));
        return ApiResponse.ok(vo);
    }

    @GetMapping
    public ApiResponse<PageResult<OtaRolloutVO>> page(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) String status,
            Authentication authentication) {
        return ApiResponse.ok(rolloutService.page(
                PageParams.current(current), PageParams.size(size, 100),
                productId, status, dataScopeService.current(), authentication.getName()));
    }

    @GetMapping("/{id}")
    public ApiResponse<OtaRolloutDetailVO> detail(@PathVariable Long id,
                                                   Authentication authentication) {
        return ApiResponse.ok(rolloutService.detail(id, dataScopeService.current(),
                authentication.getName()));
    }

    @PostMapping("/{id}/pause")
    public ApiResponse<OtaRolloutDetailVO> pause(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) OtaRolloutPauseRequest request,
            Authentication authentication) {
        String reason = request == null ? null : request.getReason();
        OtaRolloutDetailVO vo = rolloutService.pause(id, dataScopeService.current(),
                authentication.getName(), reason);
        auditLogService.record("OTA_ROLLOUT_PAUSE", "ota_rollout", Map.of(
                "id", String.valueOf(id), "reason", String.valueOf(vo.getPauseReason())));
        return ApiResponse.ok(vo);
    }

    @PostMapping("/{id}/resume")
    public ApiResponse<OtaRolloutDetailVO> resume(@PathVariable Long id,
                                                   Authentication authentication) {
        OtaRolloutDetailVO vo = rolloutService.resume(id, dataScopeService.current(),
                authentication.getName());
        auditLogService.record("OTA_ROLLOUT_RESUME", "ota_rollout",
                Map.of("id", String.valueOf(id)));
        return ApiResponse.ok(vo);
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<OtaRolloutDetailVO> cancel(@PathVariable Long id,
                                                   Authentication authentication) {
        OtaRolloutDetailVO vo = rolloutService.cancel(id, dataScopeService.current(),
                authentication.getName());
        auditLogService.record("OTA_ROLLOUT_CANCEL", "ota_rollout",
                Map.of("id", String.valueOf(id)));
        return ApiResponse.ok(vo);
    }
}
