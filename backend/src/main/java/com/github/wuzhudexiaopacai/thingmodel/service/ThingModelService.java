package com.github.wuzhudexiaopacai.thingmodel.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.wuzhudexiaopacai.common.BizException;
import com.github.wuzhudexiaopacai.product.entity.Product;
import com.github.wuzhudexiaopacai.product.mapper.ProductMapper;
import com.github.wuzhudexiaopacai.thingmodel.dto.ThingModelSaveRequest;
import com.github.wuzhudexiaopacai.thingmodel.entity.ThingModel;
import com.github.wuzhudexiaopacai.thingmodel.mapper.ThingModelMapper;
import com.github.wuzhudexiaopacai.thingmodel.vo.ThingModelVO;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class ThingModelService extends ServiceImpl<ThingModelMapper, ThingModel> {

    private final ProductMapper productMapper;

    /** 遥测校验热路径使用：产品属性定义缓存 60s */
    private final Cache<Long, List<ThingModel>> propertyCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(60))
            .maximumSize(2_000)
            .build();
    private final Cache<Long, List<ThingModel>> serviceCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(60))
            .maximumSize(2_000)
            .build();

    @Transactional(rollbackFor = Exception.class)
    public List<ThingModelVO> save(Long productId, ThingModelSaveRequest request) {
        Product product = productMapper.selectById(productId);
        if (product == null) {
            throw new BizException(404, "产品不存在");
        }

        Set<String> keys = new HashSet<>();
        for (ThingModelSaveRequest.Item item : request.getItems()) {
            if (!keys.add(item.getType() + ":" + item.getIdentifier())) {
                throw new BizException("物模型标识重复: " + item.getIdentifier());
            }
            if ("PROPERTY".equals(item.getType()) && !StringUtils.hasText(item.getDataType())) {
                throw new BizException("属性 " + item.getIdentifier() + " 必须指定 dataType");
            }
        }

        // 全量替换：逻辑删除旧定义后重新写入
        remove(Wrappers.<ThingModel>lambdaQuery().eq(ThingModel::getProductId, productId));

        List<ThingModel> entities = new ArrayList<>(request.getItems().size());
        for (ThingModelSaveRequest.Item item : request.getItems()) {
            ThingModel model = new ThingModel();
            model.setProductId(productId);
            model.setType(item.getType());
            model.setIdentifier(item.getIdentifier());
            model.setName(item.getName());
            model.setDataType(item.getDataType());
            model.setAccessMode(StringUtils.hasText(item.getAccessMode()) ? item.getAccessMode() : "R");
            model.setUnit(item.getUnit());
            model.setSpec(item.getSpec() == null ? java.util.Map.of() : item.getSpec());
            entities.add(model);
        }
        saveBatch(entities);
        propertyCache.invalidate(productId);
        serviceCache.invalidate(productId);
        return list(productId);
    }

    public List<ThingModelVO> list(Long productId) {
        return list(Wrappers.<ThingModel>lambdaQuery()
                .eq(ThingModel::getProductId, productId)
                .orderByAsc(ThingModel::getType)
                .orderByAsc(ThingModel::getIdentifier))
                .stream()
                .map(this::toVO)
                .toList();
    }

    /** 遥测校验用：仅属性定义，带本地缓存。 */
    public List<ThingModel> propertiesCached(Long productId) {
        return propertyCache.get(productId, id -> list(Wrappers.<ThingModel>lambdaQuery()
                .eq(ThingModel::getProductId, id)
                .eq(ThingModel::getType, "PROPERTY")));
    }

    /** 命令下发校验：仅服务定义，带本地缓存。 */
    public List<ThingModel> servicesCached(Long productId) {
        return serviceCache.get(productId, id -> list(Wrappers.<ThingModel>lambdaQuery()
                .eq(ThingModel::getProductId, id)
                .eq(ThingModel::getType, "SERVICE")));
    }

    private ThingModelVO toVO(ThingModel model) {
        ThingModelVO vo = new ThingModelVO();
        BeanUtils.copyProperties(model, vo);
        return vo;
    }
}
