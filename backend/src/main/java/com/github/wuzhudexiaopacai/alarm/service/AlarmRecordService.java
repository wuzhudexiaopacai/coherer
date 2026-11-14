package com.github.wuzhudexiaopacai.alarm.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.github.wuzhudexiaopacai.alarm.AlarmCondition;
import com.github.wuzhudexiaopacai.alarm.ConditionNode;
import com.github.wuzhudexiaopacai.alarm.entity.AlarmRecord;
import com.github.wuzhudexiaopacai.alarm.entity.AlarmRule;
import com.github.wuzhudexiaopacai.alarm.mapper.AlarmRecordMapper;
import com.github.wuzhudexiaopacai.alarm.vo.AlarmRecordVO;
import com.github.wuzhudexiaopacai.common.BizException;
import com.github.wuzhudexiaopacai.common.PageResult;
import com.github.wuzhudexiaopacai.device.entity.Device;
import com.github.wuzhudexiaopacai.security.DataScope;
import com.github.wuzhudexiaopacai.space.service.SpaceService;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 告警评估与告警记录生命周期。
 *
 * <p>持续时长与活动认领放在 Redis（{@link AlarmStateService}），因此多实例部署下
 * 同一设备的消息无论落到哪个实例，计时与去重结果一致；数据库活动告警作为最终事实源，
 * Redis 仅用于跨实例协调与降低查询压力。</p>
 */
@Service
@RequiredArgsConstructor
public class AlarmRecordService extends ServiceImpl<AlarmRecordMapper, AlarmRecord> {

    private static final Logger log = LoggerFactory.getLogger(AlarmRecordService.class);

    private final AlarmRuleService alarmRuleService;
    private final SpaceService spaceService;
    private final AlarmStateService alarmStateService;
    private final MeterRegistry meterRegistry;

    /** 每条遥测校验入库后调用：评估该产品下所有启用规则。 */
    public void evaluate(Device device, Map<String, Object> params) {
        try {
            for (AlarmRule rule : alarmRuleService.enabledRules(device.getProductId())) {
                ConditionNode tree = resolveTree(rule);
                if (tree == null) {
                    continue;
                }
                boolean active = alarmStateService.isActive(rule.getId(), device.getDeviceId())
                        || hasActive(rule.getId(), device.getDeviceId());

                if (AlarmCondition.evaluate(tree, params, false)) {
                    long now = System.currentTimeMillis();
                    long since = alarmStateService.conditionSince(rule.getId(), device.getDeviceId(), now);
                    int duration = rule.getDurationSeconds() == null ? 0 : rule.getDurationSeconds();
                    if (duration > 0 && now - since < duration * 1000L) {
                        continue;
                    }
                    if (!active && alarmStateService.claimActive(rule.getId(), device.getDeviceId())) {
                        try {
                            trigger(rule, device, AlarmCondition.firstNumericValue(tree, params), tree);
                        } catch (Exception e) {
                            // 落库失败必须归还认领，否则该规则/设备会长时间无法再次告警
                            alarmStateService.releaseActive(rule.getId(), device.getDeviceId());
                            throw e;
                        }
                    }
                } else {
                    alarmStateService.clearCondition(rule.getId(), device.getDeviceId());
                    // 活动中的告警用恢复阈值（滞回）判断是否真正恢复
                    if (active && !AlarmCondition.evaluate(tree, params, true)) {
                        recoverIfActive(rule, device);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("告警评估失败 deviceId={} err={}", device.getDeviceId(), e.getMessage());
        }
    }

    /** 兼容旧规则：condition_tree 为空时由 legacy 单条件字段构造叶子节点。 */
    private ConditionNode resolveTree(AlarmRule rule) {
        ConditionNode tree = rule.getConditionTree();
        if (tree != null && (tree.isGroup() || tree.getProperty() != null)) {
            return tree;
        }
        if (rule.getPropertyIdentifier() == null || rule.getOperator() == null
                || rule.getThreshold() == null) {
            return null;
        }
        ConditionNode leaf = new ConditionNode();
        leaf.setProperty(rule.getPropertyIdentifier());
        leaf.setOperator(rule.getOperator());
        leaf.setThreshold(rule.getThreshold());
        return leaf;
    }

    /**
     * @param spaceIds 服务端解析后的空间范围；null 表示不限制
     */
    public PageResult<AlarmRecordVO> page(long current, long size, String status,
                                          String level, String deviceId, List<Long> spaceIds) {
        List<String> spaceDeviceIds = spaceIds == null
                ? null
                : spaceService.deviceIdsInSpaces(spaceIds);
        if (spaceDeviceIds != null && spaceDeviceIds.isEmpty()) {
            return new PageResult<>(0, current, size, List.of());
        }
        Page<AlarmRecord> page = page(new Page<>(current, size),
                Wrappers.<AlarmRecord>lambdaQuery()
                        .eq(StringUtils.hasText(status), AlarmRecord::getStatus, status)
                        .eq(StringUtils.hasText(level), AlarmRecord::getLevel, level)
                        .eq(StringUtils.hasText(deviceId), AlarmRecord::getDeviceId, deviceId)
                        .in(spaceDeviceIds != null, AlarmRecord::getDeviceId,
                                spaceDeviceIds == null ? List.of() : spaceDeviceIds)
                        .orderByDesc(AlarmRecord::getTriggeredAt));

        Map<Long, String> ruleNames = alarmRuleService.names(
                page.getRecords().stream().map(AlarmRecord::getRuleId).toList());
        return PageResult.of(page.convert(record -> {
            AlarmRecordVO vo = new AlarmRecordVO();
            vo.setId(record.getId());
            vo.setRuleId(record.getRuleId());
            vo.setRuleName(ruleNames.getOrDefault(record.getRuleId(), "#" + record.getRuleId()));
            vo.setProductId(record.getProductId());
            vo.setDeviceId(record.getDeviceId());
            vo.setLevel(record.getLevel());
            vo.setStatus(record.getStatus());
            vo.setMessage(record.getMessage());
            vo.setValue(record.getValue());
            vo.setTriggeredAt(record.getTriggeredAt());
            vo.setAckedAt(record.getAckedAt());
            vo.setRecoveredAt(record.getRecoveredAt());
            vo.setClosedAt(record.getClosedAt());
            return vo;
        }));
    }

    @Transactional(rollbackFor = Exception.class)
    public void acknowledge(Long id, DataScope scope) {
        AlarmRecord record = requireRecord(id, scope);
        if (!"TRIGGERED".equals(record.getStatus())) {
            throw new BizException(409, "仅触发状态的告警可确认");
        }
        record.setStatus("ACKNOWLEDGED");
        record.setAckedAt(OffsetDateTime.now());
        updateById(record);
        log.info("告警已确认 id={}", id);
    }

    @Transactional(rollbackFor = Exception.class)
    public void close(Long id, DataScope scope) {
        AlarmRecord record = requireRecord(id, scope);
        if ("CLOSED".equals(record.getStatus()) || "RECOVERED".equals(record.getStatus())) {
            throw new BizException(409, "告警已结束，无需关闭");
        }
        record.setStatus("CLOSED");
        record.setClosedAt(OffsetDateTime.now());
        updateById(record);
        // 人工关闭同样释放认领，避免该规则/设备永久无法再触发
        alarmStateService.releaseActive(record.getRuleId(), record.getDeviceId());
        log.info("告警已关闭 id={}", id);
    }

    private void trigger(AlarmRule rule, Device device, Double value, ConditionNode tree) {
        AlarmRecord record = new AlarmRecord();
        record.setTenantId(device.getTenantId());
        record.setRuleId(rule.getId());
        record.setProductId(device.getProductId());
        record.setDeviceId(device.getDeviceId());
        record.setLevel(rule.getLevel());
        record.setStatus("TRIGGERED");
        record.setValue(value);
        record.setTriggeredAt(OffsetDateTime.now());
        String current = value == null ? "" : String.format("（当前 %.2f）", value);
        record.setMessage(rule.getName() + "：" + tree.summary() + current);
        save(record);
        meterRegistry.counter("coherer.alarm.triggered", "level", rule.getLevel()).increment();
        log.info("告警触发 rule={} deviceId={} value={}", rule.getName(), device.getDeviceId(), value);
    }

    private boolean hasActive(Long ruleId, String deviceId) {
        Long count = baseMapper.selectCount(Wrappers.<AlarmRecord>lambdaQuery()
                .eq(AlarmRecord::getRuleId, ruleId)
                .eq(AlarmRecord::getDeviceId, deviceId)
                .in(AlarmRecord::getStatus, List.of("TRIGGERED", "ACKNOWLEDGED")));
        return count != null && count > 0;
    }

    private void recoverIfActive(AlarmRule rule, Device device) {
        int updated = baseMapper.update(null, Wrappers.<AlarmRecord>lambdaUpdate()
                .eq(AlarmRecord::getRuleId, rule.getId())
                .eq(AlarmRecord::getDeviceId, device.getDeviceId())
                .in(AlarmRecord::getStatus, List.of("TRIGGERED", "ACKNOWLEDGED"))
                .set(AlarmRecord::getStatus, "RECOVERED")
                .set(AlarmRecord::getRecoveredAt, OffsetDateTime.now()));
        if (updated > 0) {
            // 恢复后释放认领，条件再次成立时可以重新触发
            alarmStateService.releaseActive(rule.getId(), device.getDeviceId());
            log.info("告警恢复 rule={} deviceId={}", rule.getName(), device.getDeviceId());
        }
    }

    private AlarmRecord requireRecord(Long id, DataScope scope) {
        AlarmRecord record = getById(id);
        if (record == null) {
            throw new BizException(404, "告警记录不存在");
        }
        if (scope != null && !scope.unrestricted()) {
            List<String> allowedDevices = spaceService.deviceIdsInSpaces(scope.spaceIds());
            if (!allowedDevices.contains(record.getDeviceId())) {
                throw new BizException(403, "无权操作该告警");
            }
        }
        return record;
    }
}
