package com.github.wuzhudexiaopacai.command.controller;

import com.github.wuzhudexiaopacai.audit.AuditLogService;
import com.github.wuzhudexiaopacai.command.dto.CommandSendRequest;
import com.github.wuzhudexiaopacai.command.service.CommandService;
import com.github.wuzhudexiaopacai.command.vo.CommandVO;
import com.github.wuzhudexiaopacai.common.ApiResponse;
import com.github.wuzhudexiaopacai.security.DeviceAccessService;
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
@RequestMapping("/api/devices/{deviceId}/commands")
@RequiredArgsConstructor
public class DeviceCommandController {

    private final CommandService commandService;
    private final DeviceAccessService deviceAccessService;
    private final AuditLogService auditLogService;

    @PostMapping
    public ApiResponse<CommandVO> send(@PathVariable String deviceId,
                                       @Valid @RequestBody CommandSendRequest request,
                                       Authentication authentication) {
        deviceAccessService.require(deviceId);
        CommandVO vo = commandService.send(deviceId, request, authentication.getName());
        auditLogService.record("COMMAND_SEND", "device", Map.of(
                "deviceId", deviceId,
                "identifier", request.getIdentifier(),
                "requestId", vo.getRequestId(),
                "status", vo.getStatus()));
        return ApiResponse.ok(vo);
    }

    @GetMapping
    public ApiResponse<List<CommandVO>> recent(@PathVariable String deviceId,
                                               @RequestParam(defaultValue = "20") int limit) {
        deviceAccessService.require(deviceId);
        return ApiResponse.ok(commandService.recent(deviceId, limit));
    }
}
