package com.github.wuzhudexiaopacai.alarm.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.wuzhudexiaopacai.alarm.entity.AlarmRecord;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class AlarmStateServiceTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private AlarmStateService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        service = new AlarmStateService(redisTemplate, 3600, 86400);
    }

    @Test
    void conditionSince_firstCallStoresNow() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true);

        assertEquals(1000L, service.conditionSince(1L, "dev-1", 1000L));
        verify(valueOperations).setIfAbsent("coherer:alarm:since:1:dev-1", "1000",
                Duration.ofSeconds(3600));
    }

    @Test
    void conditionSince_existingKeyReturnsOriginalTimestamp() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(false);
        when(valueOperations.get("coherer:alarm:since:1:dev-1")).thenReturn("500");

        // 多实例共享同一计时起点
        assertEquals(500L, service.conditionSince(1L, "dev-1", 9000L));
    }

    @Test
    void conditionSince_redisDown_fallsBackToNow() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RuntimeException("redis down"));

        assertEquals(7000L, service.conditionSince(1L, "dev-1", 7000L));
    }

    @Test
    void claimActive_firstInstanceWins() {
        when(valueOperations.setIfAbsent(anyString(), eq("1"), any(Duration.class)))
                .thenReturn(true, false);

        assertTrue(service.claimActive(1L, "dev-1"));
        assertFalse(service.claimActive(1L, "dev-1"));
    }

    @Test
    void claimActive_redisDown_allowsTrigger() {
        when(valueOperations.setIfAbsent(anyString(), eq("1"), any(Duration.class)))
                .thenThrow(new RuntimeException("redis down"));

        // 降级为放行：由数据库 hasActive 兜底，避免漏告警
        assertTrue(service.claimActive(1L, "dev-1"));
    }

    @Test
    void warmUp_writesClaimForActiveRecords() {
        AlarmRecord record = new AlarmRecord();
        record.setRuleId(1L);
        record.setDeviceId("dev-1");

        service.warmUp(List.of(record));

        verify(valueOperations).set("coherer:alarm:active:1:dev-1", "1",
                Duration.ofSeconds(86400));
    }

    @Test
    void releaseActive_deletesClaimKey() {
        service.releaseActive(1L, "dev-1");

        verify(redisTemplate).delete("coherer:alarm:active:1:dev-1");
    }

    @Test
    void clearCondition_deletesSinceKey() {
        service.clearCondition(1L, "dev-1");

        verify(redisTemplate).delete("coherer:alarm:since:1:dev-1");
    }
}
