package com.github.wuzhudexiaopacai.alarm.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.github.wuzhudexiaopacai.alarm.entity.AlarmRecord;
import com.github.wuzhudexiaopacai.alarm.entity.AlarmRule;
import com.github.wuzhudexiaopacai.alarm.mapper.AlarmRecordMapper;
import com.github.wuzhudexiaopacai.device.entity.Device;
import com.github.wuzhudexiaopacai.space.service.SpaceService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class AlarmRecordServiceTest {

    private AlarmRuleService ruleService;
    private AlarmRecordMapper mapper;
    private AlarmStateService stateService;
    private AlarmRecordService service;
    private Device device;

    @BeforeEach
    void setUp() {
        // 纯单元测试无 Spring 容器，手动初始化 MyBatis-Plus 表元数据（Lambda 缓存）
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), AlarmRecord.class);

        ruleService = mock(AlarmRuleService.class);
        mapper = mock(AlarmRecordMapper.class);
        stateService = mock(AlarmStateService.class);
        // 默认：无活动告警、认领成功、条件计时从当前开始
        when(stateService.activeRuleIds("dev-0001")).thenReturn(Optional.of(Set.of()));
        when(stateService.claimActive(any(), any())).thenReturn(true);
        when(mapper.selectList(any())).thenReturn(List.of());
        when(stateService.conditionSince(any(), any(), org.mockito.ArgumentMatchers.anyLong()))
                .thenAnswer(invocation -> invocation.getArgument(2));
        service = new AlarmRecordService(ruleService, mock(SpaceService.class),
                stateService, new SimpleMeterRegistry());
        ReflectionTestUtils.setField(service, "baseMapper", mapper);

        device = new Device();
        device.setDeviceId("dev-0001");
        device.setTenantId("default");
        device.setProductId(10L);
    }

    @Test
    void evaluate_conditionMatched_triggersAlarmRecord() {
        when(ruleService.enabledRules(10L)).thenReturn(List.of(rule("GT", 30.0, 0)));

        service.evaluate(device, Map.of("temperature", 35.0));

        ArgumentCaptor<AlarmRecord> captor = ArgumentCaptor.forClass(AlarmRecord.class);
        verify(mapper).insert(captor.capture());
        AlarmRecord record = captor.getValue();
        assertEquals("TRIGGERED", record.getStatus());
        assertEquals("dev-0001", record.getDeviceId());
        assertEquals(35.0, record.getValue());
        assertTrue(record.getMessage().contains("temperature"));
    }

    @Test
    void evaluate_conditionMatchedButDurationNotElapsed_doesNotTrigger() {
        when(ruleService.enabledRules(10L)).thenReturn(List.of(rule("GT", 30.0, 60)));

        service.evaluate(device, Map.of("temperature", 35.0));

        verify(mapper, never()).insert(any(AlarmRecord.class));
    }

    @Test
    void evaluate_conditionMatchedAndAlreadyActive_doesNotDuplicate() {
        when(ruleService.enabledRules(10L)).thenReturn(List.of(rule("GT", 30.0, 0)));
        when(stateService.activeRuleIds("dev-0001"))
                .thenReturn(Optional.of(Set.of(1L)));

        service.evaluate(device, Map.of("temperature", 35.0));

        verify(mapper, never()).insert(any(AlarmRecord.class));
    }

    @Test
    void evaluate_conditionRecovered_marksActiveAlarmsRecovered() {
        when(ruleService.enabledRules(10L)).thenReturn(List.of(rule("GT", 100.0, 0)));
        when(stateService.activeRuleIds("dev-0001"))
                .thenReturn(Optional.of(Set.of(1L)));
        when(mapper.update(isNull(), any())).thenReturn(1);

        service.evaluate(device, Map.of("temperature", 35.0));

        verify(mapper).update(isNull(), any());
        // 恢复后释放认领，允许条件再次成立时重新触发
        verify(stateService).releaseActive(1L, "dev-0001");
    }

    @Test
    void evaluate_redisAlreadyClaimed_doesNotInsert() {
        // 另一个实例已认领（Redis 集合中存在，但本实例数据库副本还没看到）
        when(ruleService.enabledRules(10L)).thenReturn(List.of(rule("GT", 30.0, 0)));
        when(stateService.activeRuleIds("dev-0001"))
                .thenReturn(Optional.of(Set.of(1L)));

        service.evaluate(device, Map.of("temperature", 35.0));

        verify(mapper, never()).insert(any(AlarmRecord.class));
    }

    @Test
    void evaluate_claimFailed_doesNotInsert() {
        // 多实例并发：本实例未抢到认领权
        when(ruleService.enabledRules(10L)).thenReturn(List.of(rule("GT", 30.0, 0)));
        when(stateService.claimActive(1L, "dev-0001")).thenReturn(false);

        service.evaluate(device, Map.of("temperature", 35.0));

        verify(mapper, never()).insert(any(AlarmRecord.class));
    }

    @Test
    void evaluate_insertFails_releasesClaim() {
        when(ruleService.enabledRules(10L)).thenReturn(List.of(rule("GT", 30.0, 0)));
        when(mapper.insert(any(AlarmRecord.class))).thenThrow(new RuntimeException("db down"));

        service.evaluate(device, Map.of("temperature", 35.0));

        // 落库失败必须归还认领，否则该规则/设备永久无法再次告警
        verify(stateService).releaseActive(1L, "dev-0001");
    }

    @Test
    void evaluate_noActiveAlarms_queriesDatabaseOncePer30s() {
        when(ruleService.enabledRules(10L)).thenReturn(List.of(rule("GT", 100.0, 0)));

        service.evaluate(device, Map.of("temperature", 35.0));
        service.evaluate(device, Map.of("temperature", 35.0));

        // Redis 为空时只批量查一次数据库，不再每条规则/每条消息查一次
        verify(mapper, times(1)).selectList(any());
    }

    private AlarmRule rule(String operator, double threshold, int durationSeconds) {
        AlarmRule rule = new AlarmRule();
        rule.setId(1L);
        rule.setProductId(10L);
        rule.setName("高温告警");
        rule.setPropertyIdentifier("temperature");
        rule.setOperator(operator);
        rule.setThreshold(threshold);
        rule.setDurationSeconds(durationSeconds);
        rule.setLevel("WARNING");
        rule.setEnabled(true);
        return rule;
    }
}
