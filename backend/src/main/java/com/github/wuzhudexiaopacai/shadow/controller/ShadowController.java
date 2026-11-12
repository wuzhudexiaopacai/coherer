package com.github.wuzhudexiaopacai.shadow.controller;

import com.github.wuzhudexiaopacai.common.ApiResponse;
import com.github.wuzhudexiaopacai.shadow.dto.ShadowDesiredRequest;
import com.github.wuzhudexiaopacai.shadow.service.ShadowService;
import com.github.wuzhudexiaopacai.shadow.vo.ShadowVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/devices/{deviceId}/shadow")
@RequiredArgsConstructor
public class ShadowController {

    private final ShadowService shadowService;

    @GetMapping
    public ApiResponse<ShadowVO> get(@PathVariable String deviceId) {
        return ApiResponse.ok(shadowService.get(deviceId));
    }

    @PutMapping("/desired")
    public ApiResponse<ShadowVO> updateDesired(@PathVariable String deviceId,
                                               @Valid @RequestBody ShadowDesiredRequest request) {
        return ApiResponse.ok(shadowService.updateDesired(deviceId, request));
    }
}
