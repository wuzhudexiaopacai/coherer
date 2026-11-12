package com.github.wuzhudexiaopacai.product.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.github.wuzhudexiaopacai.common.BizException;
import com.github.wuzhudexiaopacai.common.Ids;
import com.github.wuzhudexiaopacai.common.PageResult;
import com.github.wuzhudexiaopacai.device.entity.Device;
import com.github.wuzhudexiaopacai.device.mapper.DeviceMapper;
import com.github.wuzhudexiaopacai.product.dto.ProductCreateRequest;
import com.github.wuzhudexiaopacai.product.dto.ProductUpdateRequest;
import com.github.wuzhudexiaopacai.product.entity.Product;
import com.github.wuzhudexiaopacai.product.mapper.ProductMapper;
import com.github.wuzhudexiaopacai.product.vo.ProductVO;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class ProductService extends ServiceImpl<ProductMapper, Product> {

    private final DeviceMapper deviceMapper;

    @Transactional(rollbackFor = Exception.class)
    public ProductVO create(ProductCreateRequest request) {
        Product product = new Product();
        product.setTenantId("default");
        product.setProductKey(Ids.productKey());
        product.setName(request.getName());
        product.setCategory(request.getCategory());
        product.setNodeType(request.getNodeType());
        product.setAuthType("SECRET");
        product.setStatus("ACTIVE");
        product.setDescription(request.getDescription());
        save(product);
        return toVO(product);
    }

    public PageResult<ProductVO> page(long current, long size, String keyword) {
        Page<Product> page = page(new Page<>(current, size),
                Wrappers.<Product>lambdaQuery()
                        .and(StringUtils.hasText(keyword),
                                w -> w.like(Product::getName, keyword)
                                        .or()
                                        .like(Product::getProductKey, keyword))
                        .orderByDesc(Product::getCreatedAt));
        return PageResult.of(page.convert(this::toVO));
    }

    public ProductVO get(Long id) {
        return toVO(requireProduct(id));
    }

    @Transactional(rollbackFor = Exception.class)
    public ProductVO update(Long id, ProductUpdateRequest request) {
        Product product = requireProduct(id);
        product.setName(request.getName());
        if (request.getCategory() != null) {
            product.setCategory(request.getCategory());
        }
        if (request.getNodeType() != null) {
            product.setNodeType(request.getNodeType());
        }
        if (request.getStatus() != null) {
            product.setStatus(request.getStatus());
        }
        if (request.getDescription() != null) {
            product.setDescription(request.getDescription());
        }
        updateById(product);
        return toVO(product);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        requireProduct(id);
        Long deviceCount = deviceMapper.selectCount(
                Wrappers.<Device>lambdaQuery().eq(Device::getProductId, id));
        if (deviceCount != null && deviceCount > 0) {
            throw new BizException("该产品下仍有 " + deviceCount + " 台设备，请先删除设备");
        }
        removeById(id);
    }

    private Product requireProduct(Long id) {
        Product product = getById(id);
        if (product == null) {
            throw new BizException(404, "产品不存在");
        }
        return product;
    }

    private ProductVO toVO(Product product) {
        ProductVO vo = new ProductVO();
        BeanUtils.copyProperties(product, vo);
        return vo;
    }
}
