package com.github.wuzhudexiaopacai.alarm.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.wuzhudexiaopacai.alarm.dto.AlarmRuleRequest;
import com.github.wuzhudexiaopacai.alarm.dto.AlarmRuleUpdateRequest;
import com.github.wuzhudexiaopacai.alarm.entity.AlarmRule;
import com.github.wuzhudexiaopacai.alarm.mapper.AlarmRuleMapper;
import com.github.wuzhudexiaopacai.alarm.vo.AlarmRuleVO;
import com.github.wuzhudexiaopacai.common.BizException;
import com.github.wuzhudexiaopacai.common.PageResult;
import com.github.wuzhudexiaopacai.product.entity.Product;
import com.github.wuzhudexiaopacai.product.mapper.ProductMapper;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AlarmRuleService extends ServiceImpl<AlarmRuleMapper, AlarmRule> {

    private final ProductMapper productMapper;

    /** 规则热数据缓存：遥测每条消息评估时使用 */
    private final Cache<Long, List<AlarmRule>> enabledRulesCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(60))
            .maximumSize(2_000)
            .build();

    @Transactional(rollbackFor = Exception.class)
    public AlarmRuleVO create(AlarmRuleRequest request) {
        Product product = productMapper.selectById(request.getProductId());
        if (product == null) {
            throw new BizException(404, "产品不存在");
        }
        AlarmRule rule = new AlarmRule();
        rule.setTenantId(product.getTenantId());
        rule.setProductId(request.getProductId());
        rule.setName(request.getName());
        rule.setPropertyIdentifier(request.getPropertyIdentifier());
        rule.setOperator(request.getOperator());
        rule.setThreshold(request.getThreshold());
        rule.setDurationSeconds(request.getDurationSeconds() == null ? 0 : request.getDurationSeconds());
        rule.setLevel(request.getLevel() == null ? "WARNING" : request.getLevel());
        rule.setEnabled(request.getEnabled() == null || request.getEnabled());
        save(rule);
        invalidate(rule.getProductId());
        return toVO(rule);
    }

    public PageResult<AlarmRuleVO> page(long current, long size, Long productId) {
        Page<AlarmRule> page = page(new Page<>(current, size),
                Wrappers.<AlarmRule>lambdaQuery()
                        .eq(productId != null, AlarmRule::getProductId, productId)
                        .orderByDesc(AlarmRule::getCreatedAt));
        return PageResult.of(page.convert(this::toVO));
    }

    @Transactional(rollbackFor = Exception.class)
    public AlarmRuleVO update(Long id, AlarmRuleUpdateRequest request) {
        AlarmRule rule = requireRule(id);
        if (request.getName() != null) {
            rule.setName(request.getName());
        }
        if (request.getThreshold() != null) {
            rule.setThreshold(request.getThreshold());
        }
        if (request.getDurationSeconds() != null) {
            rule.setDurationSeconds(request.getDurationSeconds());
        }
        if (request.getLevel() != null) {
            rule.setLevel(request.getLevel());
        }
        if (request.getEnabled() != null) {
            rule.setEnabled(request.getEnabled());
        }
        updateById(rule);
        invalidate(rule.getProductId());
        return toVO(rule);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        AlarmRule rule = requireRule(id);
        removeById(id);
        invalidate(rule.getProductId());
    }

    /** 供告警评估使用：启用的规则列表（60s 缓存，规则变更即时失效）。 */
    public List<AlarmRule> enabledRules(Long productId) {
        return enabledRulesCache.get(productId, id -> list(Wrappers.<AlarmRule>lambdaQuery()
                .eq(AlarmRule::getProductId, id)
                .eq(AlarmRule::getEnabled, true)));
    }

    public Map<Long, String> names(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        return listByIds(ids).stream()
                .collect(Collectors.toMap(AlarmRule::getId, AlarmRule::getName, (a, b) -> a));
    }

    public void invalidate(Long productId) {
        if (productId != null) {
            enabledRulesCache.invalidate(productId);
        }
    }

    private AlarmRule requireRule(Long id) {
        AlarmRule rule = getById(id);
        if (rule == null) {
            throw new BizException(404, "告警规则不存在");
        }
        return rule;
    }

    private AlarmRuleVO toVO(AlarmRule rule) {
        AlarmRuleVO vo = new AlarmRuleVO();
        BeanUtils.copyProperties(rule, vo);
        return vo;
    }
}
