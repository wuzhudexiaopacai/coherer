package com.github.wuzhudexiaopacai.product.controller;

import com.github.wuzhudexiaopacai.common.ApiResponse;
import com.github.wuzhudexiaopacai.common.PageResult;
import com.github.wuzhudexiaopacai.product.dto.ProductCreateRequest;
import com.github.wuzhudexiaopacai.product.dto.ProductUpdateRequest;
import com.github.wuzhudexiaopacai.product.service.ProductService;
import com.github.wuzhudexiaopacai.product.vo.ProductVO;
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
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @PostMapping
    public ApiResponse<ProductVO> create(@Valid @RequestBody ProductCreateRequest request) {
        return ApiResponse.ok(productService.create(request));
    }

    @GetMapping
    public ApiResponse<PageResult<ProductVO>> page(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(productService.page(current, size, keyword));
    }

    @GetMapping("/{id}")
    public ApiResponse<ProductVO> get(@PathVariable Long id) {
        return ApiResponse.ok(productService.get(id));
    }

    @PutMapping("/{id}")
    public ApiResponse<ProductVO> update(@PathVariable Long id,
                                         @Valid @RequestBody ProductUpdateRequest request) {
        return ApiResponse.ok(productService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        productService.delete(id);
        return ApiResponse.ok();
    }
}
