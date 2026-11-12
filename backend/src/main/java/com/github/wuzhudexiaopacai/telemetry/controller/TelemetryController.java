package com.github.wuzhudexiaopacai.telemetry.controller;

import com.github.wuzhudexiaopacai.common.ApiResponse;
import com.github.wuzhudexiaopacai.telemetry.service.TelemetryService;
import com.github.wuzhudexiaopacai.telemetry.vo.TelemetryVO;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/devices/{deviceId}/telemetry")
@RequiredArgsConstructor
public class TelemetryController {

    private final TelemetryService telemetryService;

    /** 最近遥测（M2 提供按时间范围 + 聚合查询）。 */
    @GetMapping
    public ApiResponse<List<TelemetryVO>> recent(
            @PathVariable String deviceId,
            @RequestParam(defaultValue = "20") int limit) {
        int size = Math.min(Math.max(limit, 1), 200);
        return ApiResponse.ok(telemetryService.recent(deviceId, size));
    }
}
