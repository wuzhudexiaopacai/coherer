package com.github.wuzhudexiaopacai.thingmodel.controller;

import com.github.wuzhudexiaopacai.common.ApiResponse;
import com.github.wuzhudexiaopacai.thingmodel.dto.ThingModelSaveRequest;
import com.github.wuzhudexiaopacai.thingmodel.service.ThingModelService;
import com.github.wuzhudexiaopacai.thingmodel.vo.ThingModelVO;
import jakarta.validation.Valid;
import java.util.List;
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

    @PutMapping
    public ApiResponse<List<ThingModelVO>> save(@PathVariable Long productId,
                                                @Valid @RequestBody ThingModelSaveRequest request) {
        return ApiResponse.ok(thingModelService.save(productId, request));
    }

    @GetMapping
    public ApiResponse<List<ThingModelVO>> list(@PathVariable Long productId) {
        return ApiResponse.ok(thingModelService.list(productId));
    }
}
