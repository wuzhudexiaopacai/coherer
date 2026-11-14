package com.github.wuzhudexiaopacai.event.controller;

import com.github.wuzhudexiaopacai.common.ApiResponse;
import com.github.wuzhudexiaopacai.event.service.EventService;
import com.github.wuzhudexiaopacai.event.vo.DeviceEventVO;
import com.github.wuzhudexiaopacai.security.DeviceAccessService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/devices/{deviceId}/events")
@RequiredArgsConstructor
public class DeviceEventController {

    private final EventService eventService;
    private final DeviceAccessService deviceAccessService;

    @GetMapping
    public ApiResponse<List<DeviceEventVO>> recent(@PathVariable String deviceId,
                                                   @RequestParam(defaultValue = "20") int limit) {
        deviceAccessService.require(deviceId);
        return ApiResponse.ok(eventService.recent(deviceId, limit));
    }
}
