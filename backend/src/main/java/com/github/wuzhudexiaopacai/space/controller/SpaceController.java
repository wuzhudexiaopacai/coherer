package com.github.wuzhudexiaopacai.space.controller;

import com.github.wuzhudexiaopacai.common.ApiResponse;
import com.github.wuzhudexiaopacai.space.dto.SpaceRequest;
import com.github.wuzhudexiaopacai.space.dto.SpaceUpdateRequest;
import com.github.wuzhudexiaopacai.space.service.SpaceService;
import com.github.wuzhudexiaopacai.space.vo.SpaceNodeVO;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/spaces")
@RequiredArgsConstructor
public class SpaceController {

    private final SpaceService spaceService;

    @GetMapping("/tree")
    public ApiResponse<List<SpaceNodeVO>> tree() {
        return ApiResponse.ok(spaceService.tree());
    }

    @PostMapping
    public ApiResponse<SpaceNodeVO> create(@Valid @RequestBody SpaceRequest request) {
        return ApiResponse.ok(spaceService.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<SpaceNodeVO> update(@PathVariable Long id,
                                           @Valid @RequestBody SpaceUpdateRequest request) {
        return ApiResponse.ok(spaceService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        spaceService.delete(id);
        return ApiResponse.ok();
    }
}
