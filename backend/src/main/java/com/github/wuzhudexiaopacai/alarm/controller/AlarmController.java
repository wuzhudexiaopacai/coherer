package com.github.wuzhudexiaopacai.alarm.controller;

import com.github.wuzhudexiaopacai.alarm.service.AlarmRecordService;
import com.github.wuzhudexiaopacai.alarm.vo.AlarmRecordVO;
import com.github.wuzhudexiaopacai.audit.AuditLogService;
import com.github.wuzhudexiaopacai.common.ApiResponse;
import com.github.wuzhudexiaopacai.common.PageParams;
import com.github.wuzhudexiaopacai.common.PageResult;
import com.github.wuzhudexiaopacai.security.DataScope;
import com.github.wuzhudexiaopacai.security.DataScopeService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/alarms")
@RequiredArgsConstructor
public class AlarmController {

    private final AlarmRecordService alarmRecordService;
    private final DataScopeService dataScopeService;
    private final AuditLogService auditLogService;

    @GetMapping
    public ApiResponse<PageResult<AlarmRecordVO>> page(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String deviceId,
            @RequestParam(required = false) Long spaceId) {
        return ApiResponse.ok(alarmRecordService.page(
                PageParams.current(current), PageParams.size(size, 100),
                status, level, deviceId, dataScopeService.resolveSpaceIds(spaceId)));
    }

    @PostMapping("/{id}/ack")
    public ApiResponse<Void> acknowledge(@PathVariable Long id) {
        alarmRecordService.acknowledge(id, dataScopeService.current());
        auditLogService.record("ALARM_ACK", "alarm", Map.of("id", String.valueOf(id)));
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/close")
    public ApiResponse<Void> close(@PathVariable Long id) {
        alarmRecordService.close(id, dataScopeService.current());
        auditLogService.record("ALARM_CLOSE", "alarm", Map.of("id", String.valueOf(id)));
        return ApiResponse.ok();
    }
}
