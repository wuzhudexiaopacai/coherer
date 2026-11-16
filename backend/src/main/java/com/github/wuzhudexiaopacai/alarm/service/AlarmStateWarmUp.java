package com.github.wuzhudexiaopacai.alarm.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.wuzhudexiaopacai.alarm.entity.AlarmRecord;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 启动时把数据库中仍在活动的告警回填到 Redis 认领标记。
 *
 * <p>Redis 被清空/重启后，若不回填，新实例会对已存在的活动告警再次插入记录。
 * 回填是幂等操作，多实例同时启动也不会产生副作用。</p>
 */
@Component
@RequiredArgsConstructor
public class AlarmStateWarmUp {

    private static final Logger log = LoggerFactory.getLogger(AlarmStateWarmUp.class);

    private final AlarmRecordService alarmRecordService;
    private final AlarmStateService alarmStateService;

    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        com.github.wuzhudexiaopacai.security.TenantContext.runBypass(() -> {
            try {
                List<AlarmRecord> active = alarmRecordService.list(
                        Wrappers.<AlarmRecord>lambdaQuery()
                                .select(AlarmRecord::getId, AlarmRecord::getRuleId,
                                        AlarmRecord::getDeviceId)
                                .in(AlarmRecord::getStatus, List.of("TRIGGERED", "ACKNOWLEDGED")));
                if (active.isEmpty()) {
                    return;
                }
                alarmStateService.warmUp(active);
                log.info("告警状态预热完成，活动告警 {} 条", active.size());
            } catch (Exception e) {
                log.warn("告警状态预热失败（不影响启动）: {}", e.getMessage());
            }
        });
    }
}
