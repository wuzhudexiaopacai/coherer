package com.github.wuzhudexiaopacai.alarm.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.github.wuzhudexiaopacai.alarm.entity.AlarmRecord;
import com.github.wuzhudexiaopacai.alarm.entity.AlarmRule;
import com.github.wuzhudexiaopacai.alarm.mapper.AlarmRecordMapper;
import com.github.wuzhudexiaopacai.device.entity.Device;
import com.github.wuzhudexiaopacai.space.service.SpaceService;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class AlarmRecordServiceTest {

    private AlarmRuleService ruleService;
    private AlarmRecordMapper mapper;
    private AlarmRecordService service;
    private Device device;

    @BeforeEach
    void setUp() {
        // 纯单元测试无 Spring 容器，手动初始化 MyBatis-Plus 表元数据（Lambda 缓存）
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), AlarmRecord.class);

        ruleService = mock(AlarmRuleService.class);
        mapper = mock(AlarmRecordMapper.class);
        service = new AlarmRecordService(ruleService, mock(SpaceService.class));
        ReflectionTestUtils.setField(service, "baseMapper", mapper);

        device = new Device();
        device.setDeviceId("dev-0001");
        device.setTenantId("default");
        device.setProductId(10L);
    }

    @Test
    void evaluate_conditionMatched_triggersAlarmRecord() {
        when(ruleService.enabledRules(10L)).thenReturn(List.of(rule("GT", 30.0, 0)));
        when(mapper.selectCount(any())).thenReturn(0L);

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
        when(mapper.selectCount(any())).thenReturn(1L);

        service.evaluate(device, Map.of("temperature", 35.0));

        verify(mapper, never()).insert(any(AlarmRecord.class));
    }

    @Test
    void evaluate_conditionRecovered_marksActiveAlarmsRecovered() {
        when(ruleService.enabledRules(10L)).thenReturn(List.of(rule("GT", 100.0, 0)));
        when(mapper.selectCount(any())).thenReturn(1L);

        service.evaluate(device, Map.of("temperature", 35.0));

        verify(mapper).update(isNull(), any());
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
