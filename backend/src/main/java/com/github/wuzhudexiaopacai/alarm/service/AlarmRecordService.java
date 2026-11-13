package com.github.wuzhudexiaopacai.alarm.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.wuzhudexiaopacai.alarm.AlarmCondition;
import com.github.wuzhudexiaopacai.alarm.entity.AlarmRecord;
import com.github.wuzhudexiaopacai.alarm.entity.AlarmRule;
import com.github.wuzhudexiaopacai.alarm.mapper.AlarmRecordMapper;
import com.github.wuzhudexiaopacai.alarm.vo.AlarmRecordVO;
import com.github.wuzhudexiaopacai.common.BizException;
import com.github.wuzhudexiaopacai.common.PageResult;
import com.github.wuzhudexiaopacai.device.entity.Device;
import java.time.Duration;
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
 * <p>持续时长语义：条件首次成立的时间记录在本地缓存，持续满足 N 秒后才触发；
 * 缓存为单实例内存态，重启后重新计时（M3 可迁移至 Redis）。</p>
 */
@Service
@RequiredArgsConstructor
public class AlarmRecordService extends ServiceImpl<AlarmRecordMapper, AlarmRecord> {

    private static final Logger log = LoggerFactory.getLogger(AlarmRecordService.class);

    private final AlarmRuleService alarmRuleService;

    private final Cache<String, Long> conditionStart = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofHours(1))
            .maximumSize(100_000)
            .build();

    /** 每条遥测校验入库后调用：评估该产品下所有启用规则。 */
    public void evaluate(Device device, Map<String, Object> params) {
        try {
            for (AlarmRule rule : alarmRuleService.enabledRules(device.getProductId())) {
                Object raw = params.get(rule.getPropertyIdentifier());
                if (!(raw instanceof Number number)) {
                    continue;
                }
                double value = number.doubleValue();
                String key = rule.getId() + ":" + device.getDeviceId();

                if (AlarmCondition.matches(value, rule.getOperator(), rule.getThreshold())) {
                    long now = System.currentTimeMillis();
                    long since = conditionStart.get(key, ignored -> now);
                    int duration = rule.getDurationSeconds() == null ? 0 : rule.getDurationSeconds();
                    if (duration > 0 && now - since < duration * 1000L) {
                        continue;
                    }
                    if (!hasActive(rule.getId(), device.getDeviceId())) {
                        trigger(rule, device, value);
                    }
                } else {
                    conditionStart.invalidate(key);
                    recoverIfActive(rule, device);
                }
            }
        } catch (Exception e) {
            log.warn("告警评估失败 deviceId={} err={}", device.getDeviceId(), e.getMessage());
        }
    }

    public PageResult<AlarmRecordVO> page(long current, long size, String status,
                                          String level, String deviceId) {
        Page<AlarmRecord> page = page(new Page<>(current, size),
                Wrappers.<AlarmRecord>lambdaQuery()
                        .eq(StringUtils.hasText(status), AlarmRecord::getStatus, status)
                        .eq(StringUtils.hasText(level), AlarmRecord::getLevel, level)
                        .eq(StringUtils.hasText(deviceId), AlarmRecord::getDeviceId, deviceId)
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
    public void acknowledge(Long id) {
        AlarmRecord record = requireRecord(id);
        if (!"TRIGGERED".equals(record.getStatus())) {
            throw new BizException(409, "仅触发状态的告警可确认");
        }
        record.setStatus("ACKNOWLEDGED");
        record.setAckedAt(OffsetDateTime.now());
        updateById(record);
        log.info("告警已确认 id={}", id);
    }

    @Transactional(rollbackFor = Exception.class)
    public void close(Long id) {
        AlarmRecord record = requireRecord(id);
        if ("CLOSED".equals(record.getStatus()) || "RECOVERED".equals(record.getStatus())) {
            throw new BizException(409, "告警已结束，无需关闭");
        }
        record.setStatus("CLOSED");
        record.setClosedAt(OffsetDateTime.now());
        updateById(record);
        log.info("告警已关闭 id={}", id);
    }

    private void trigger(AlarmRule rule, Device device, double value) {
        AlarmRecord record = new AlarmRecord();
        record.setTenantId(device.getTenantId());
        record.setRuleId(rule.getId());
        record.setProductId(device.getProductId());
        record.setDeviceId(device.getDeviceId());
        record.setLevel(rule.getLevel());
        record.setStatus("TRIGGERED");
        record.setValue(value);
        record.setTriggeredAt(OffsetDateTime.now());
        record.setMessage(String.format("%s：%s %s %s（当前 %.2f）",
                rule.getName(), rule.getPropertyIdentifier(),
                AlarmCondition.symbol(rule.getOperator()), rule.getThreshold(), value));
        save(record);
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
            log.info("告警恢复 rule={} deviceId={}", rule.getName(), device.getDeviceId());
        }
    }

    private AlarmRecord requireRecord(Long id) {
        AlarmRecord record = getById(id);
        if (record == null) {
            throw new BizException(404, "告警记录不存在");
        }
        return record;
    }
}
