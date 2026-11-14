package com.github.wuzhudexiaopacai.device.controller;

import com.github.wuzhudexiaopacai.common.ApiResponse;
import com.github.wuzhudexiaopacai.common.PageParams;
import com.github.wuzhudexiaopacai.common.PageResult;
import com.github.wuzhudexiaopacai.device.dto.DeviceRegisterRequest;
import com.github.wuzhudexiaopacai.device.service.DeviceService;
import com.github.wuzhudexiaopacai.device.vo.DeviceRegisterResult;
import com.github.wuzhudexiaopacai.device.vo.DeviceVO;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
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

    @PostMapping
    public ApiResponse<List<DeviceRegisterResult>> register(
            @Valid @RequestBody DeviceRegisterRequest request) {
        return ApiResponse.ok(deviceService.register(request));
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
                productId, status, keyword, spaceId));
    }

    @GetMapping("/{id}")
    public ApiResponse<DeviceVO> get(@PathVariable Long id) {
        return ApiResponse.ok(deviceService.get(id));
    }
}
