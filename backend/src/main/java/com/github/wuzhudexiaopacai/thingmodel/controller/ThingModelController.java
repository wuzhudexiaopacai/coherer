package com.github.wuzhudexiaopacai.thingmodel.controller;

import com.github.wuzhudexiaopacai.audit.AuditLogService;
import com.github.wuzhudexiaopacai.common.ApiResponse;
import com.github.wuzhudexiaopacai.thingmodel.dto.ThingModelSaveRequest;
import com.github.wuzhudexiaopacai.thingmodel.service.ThingModelService;
import com.github.wuzhudexiaopacai.security.DataScopeService;
import com.github.wuzhudexiaopacai.thingmodel.vo.ThingModelVO;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/products/{productId}/thing-model")
@RequiredArgsConstructor
public class ThingModelController {

    private final ThingModelService thingModelService;
    private final DataScopeService dataScopeService;
    private final AuditLogService auditLogService;

    @PutMapping
    public ApiResponse<List<ThingModelVO>> save(@PathVariable Long productId,
                                                @Valid @RequestBody ThingModelSaveRequest request) {
        dataScopeService.requireUnrestricted("物模型管理");
        List<ThingModelVO> items = thingModelService.save(productId, request);
        auditLogService.record("THING_MODEL_SAVE", "thing-model", Map.of(
                "productId", String.valueOf(productId),
                "count", items.size()));
        return ApiResponse.ok(items);
    }

    @GetMapping
    public ApiResponse<List<ThingModelVO>> list(@PathVariable Long productId) {
        return ApiResponse.ok(thingModelService.list(productId));
    }
}
