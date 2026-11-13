package com.github.wuzhudexiaopacai.alarm.controller;

import com.github.wuzhudexiaopacai.alarm.service.AlarmRecordService;
import com.github.wuzhudexiaopacai.alarm.vo.AlarmRecordVO;
import com.github.wuzhudexiaopacai.common.ApiResponse;
import com.github.wuzhudexiaopacai.common.PageResult;
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

    @GetMapping
    public ApiResponse<PageResult<AlarmRecordVO>> page(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String deviceId) {
        return ApiResponse.ok(alarmRecordService.page(current, size, status, level, deviceId));
    }

    @PostMapping("/{id}/ack")
    public ApiResponse<Void> acknowledge(@PathVariable Long id) {
        alarmRecordService.acknowledge(id);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/close")
    public ApiResponse<Void> close(@PathVariable Long id) {
        alarmRecordService.close(id);
        return ApiResponse.ok();
    }
}
