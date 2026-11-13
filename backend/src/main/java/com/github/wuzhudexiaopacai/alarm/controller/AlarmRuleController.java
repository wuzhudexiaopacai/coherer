package com.github.wuzhudexiaopacai.alarm.controller;

import com.github.wuzhudexiaopacai.alarm.dto.AlarmRuleRequest;
import com.github.wuzhudexiaopacai.alarm.dto.AlarmRuleUpdateRequest;
import com.github.wuzhudexiaopacai.alarm.service.AlarmRuleService;
import com.github.wuzhudexiaopacai.alarm.vo.AlarmRuleVO;
import com.github.wuzhudexiaopacai.common.ApiResponse;
import com.github.wuzhudexiaopacai.common.PageResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/alarm-rules")
@RequiredArgsConstructor
public class AlarmRuleController {

    private final AlarmRuleService alarmRuleService;

    @GetMapping
    public ApiResponse<PageResult<AlarmRuleVO>> page(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) Long productId) {
        return ApiResponse.ok(alarmRuleService.page(current, size, productId));
    }

    @PostMapping
    public ApiResponse<AlarmRuleVO> create(@Valid @RequestBody AlarmRuleRequest request) {
        return ApiResponse.ok(alarmRuleService.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<AlarmRuleVO> update(@PathVariable Long id,
                                           @Valid @RequestBody AlarmRuleUpdateRequest request) {
        return ApiResponse.ok(alarmRuleService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        alarmRuleService.delete(id);
        return ApiResponse.ok();
    }
}
