package com.github.wuzhudexiaopacai.product.controller;

import com.github.wuzhudexiaopacai.audit.AuditLogService;
import com.github.wuzhudexiaopacai.common.ApiResponse;
import com.github.wuzhudexiaopacai.common.PageParams;
import com.github.wuzhudexiaopacai.common.PageResult;
import com.github.wuzhudexiaopacai.product.dto.ProductCreateRequest;
import com.github.wuzhudexiaopacai.product.dto.ProductUpdateRequest;
import com.github.wuzhudexiaopacai.product.service.ProductService;
import com.github.wuzhudexiaopacai.product.vo.ProductVO;
import com.github.wuzhudexiaopacai.security.DataScopeService;
import jakarta.validation.Valid;
import java.util.Map;
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
    private final DataScopeService dataScopeService;
    private final AuditLogService auditLogService;

    @PostMapping
    public ApiResponse<ProductVO> create(@Valid @RequestBody ProductCreateRequest request) {
        dataScopeService.requireUnrestricted("产品管理");
        ProductVO vo = productService.create(request);
        auditLogService.record("PRODUCT_CREATE", "product", Map.of(
                "id", String.valueOf(vo.getId()),
                "name", String.valueOf(vo.getName())));
        return ApiResponse.ok(vo);
    }

    @GetMapping
    public ApiResponse<PageResult<ProductVO>> page(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(productService.page(
                PageParams.current(current), PageParams.size(size, 100), keyword));
    }

    @GetMapping("/{id}")
    public ApiResponse<ProductVO> get(@PathVariable Long id) {
        return ApiResponse.ok(productService.get(id));
    }

    @PutMapping("/{id}")
    public ApiResponse<ProductVO> update(@PathVariable Long id,
                                         @Valid @RequestBody ProductUpdateRequest request) {
        dataScopeService.requireUnrestricted("产品管理");
        ProductVO vo = productService.update(id, request);
        auditLogService.record("PRODUCT_UPDATE", "product", Map.of(
                "id", String.valueOf(id),
                "name", String.valueOf(vo.getName())));
        return ApiResponse.ok(vo);
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        dataScopeService.requireUnrestricted("产品管理");
        productService.delete(id);
        auditLogService.record("PRODUCT_DELETE", "product", Map.of("id", String.valueOf(id)));
        return ApiResponse.ok();
    }
}
