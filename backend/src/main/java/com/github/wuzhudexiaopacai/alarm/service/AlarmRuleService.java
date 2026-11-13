package com.github.wuzhudexiaopacai.alarm.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.wuzhudexiaopacai.alarm.ConditionNode;
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
import org.springframework.util.StringUtils;

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
        ConditionNode tree = resolveCondition(request);
        applyLegacySummary(rule, tree);
        rule.setConditionTree(tree);
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
        if (request.getCondition() != null) {
            validateNode(request.getCondition());
            rule.setConditionTree(request.getCondition());
            applyLegacySummary(rule, request.getCondition());
        }
        if (request.getThreshold() != null) {
            rule.setThreshold(request.getThreshold());
            ConditionNode tree = rule.getConditionTree();
            if (tree != null && !tree.isGroup()) {
                tree.setThreshold(request.getThreshold());
            }
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

    private ConditionNode resolveCondition(AlarmRuleRequest request) {
        ConditionNode tree = request.getCondition();
        if (tree == null) {
            if (!StringUtils.hasText(request.getPropertyIdentifier())
                    || !StringUtils.hasText(request.getOperator())
                    || request.getThreshold() == null) {
                throw new BizException("触发条件不能为空：请配置 condition 或单条件字段");
            }
            tree = new ConditionNode();
            tree.setProperty(request.getPropertyIdentifier());
            tree.setOperator(request.getOperator());
            tree.setThreshold(request.getThreshold());
        }
        validateNode(tree);
        return tree;
    }

    private void validateNode(ConditionNode node) {
        if (node == null) {
            throw new BizException("条件节点不能为空");
        }
        if (node.isGroup()) {
            String logic = node.getLogic() == null ? "AND" : node.getLogic().toUpperCase();
            if (!"AND".equals(logic) && !"OR".equals(logic)) {
                throw new BizException("条件逻辑只能是 AND 或 OR");
            }
            node.setLogic(logic);
            node.getItems().forEach(this::validateNode);
            return;
        }
        if (!StringUtils.hasText(node.getProperty())
                || !StringUtils.hasText(node.getOperator())
                || node.getThreshold() == null) {
            throw new BizException("条件缺少属性、比较符或阈值");
        }
        String operator = node.getOperator().toUpperCase();
        if (!List.of("GT", "GE", "LT", "LE", "EQ", "NE").contains(operator)) {
            throw new BizException("不支持的比较符: " + operator);
        }
        node.setOperator(operator);
    }

    private void applyLegacySummary(AlarmRule rule, ConditionNode tree) {
        ConditionNode leaf = tree.firstLeaf();
        if (leaf != null) {
            rule.setPropertyIdentifier(leaf.getProperty());
            rule.setOperator(leaf.getOperator());
            rule.setThreshold(leaf.getThreshold());
        }
    }

    private AlarmRuleVO toVO(AlarmRule rule) {
        AlarmRuleVO vo = new AlarmRuleVO();
        BeanUtils.copyProperties(rule, vo);
        ConditionNode condition = rule.getConditionTree();
        if (condition == null && rule.getPropertyIdentifier() != null) {
            condition = new ConditionNode();
            condition.setProperty(rule.getPropertyIdentifier());
            condition.setOperator(rule.getOperator());
            condition.setThreshold(rule.getThreshold());
        }
        vo.setCondition(condition);
        vo.setConditionText(condition == null ? "—" : condition.summary());
        return vo;
    }
}
