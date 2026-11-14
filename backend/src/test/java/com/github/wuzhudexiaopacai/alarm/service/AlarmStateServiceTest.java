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
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class AlarmStateServiceTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private SetOperations<String, String> setOperations;
    private AlarmStateService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        setOperations = mock(SetOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
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

        assertEquals(500L, service.conditionSince(1L, "dev-1", 9000L));
    }

    @Test
    void conditionSince_redisDown_fallsBackToNow() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RuntimeException("redis down"));

        assertEquals(7000L, service.conditionSince(1L, "dev-1", 7000L));
    }

    @Test
    void activeRuleIds_returnsDeviceSetInOneCall() {
        when(setOperations.members("coherer:alarm:active:dev:dev-1"))
                .thenReturn(Set.of("1", "2"));

        Optional<Set<Long>> result = service.activeRuleIds("dev-1");

        assertTrue(result.isPresent());
        assertEquals(Set.of(1L, 2L), result.get());
    }

    @Test
    void activeRuleIds_emptySetIsPresent() {
        when(setOperations.members(anyString())).thenReturn(Set.of());

        Optional<Set<Long>> result = service.activeRuleIds("dev-1");

        assertTrue(result.isPresent());
        assertTrue(result.get().isEmpty());
    }

    @Test
    void activeRuleIds_redisDown_isEmptyOptional() {
        when(setOperations.members(anyString())).thenThrow(new RuntimeException("redis down"));

        assertTrue(service.activeRuleIds("dev-1").isEmpty());
    }

    @Test
    void claimActive_firstInstanceWins() {
        when(setOperations.add(anyString(), any(String[].class))).thenReturn(1L, 0L);

        assertTrue(service.claimActive(1L, "dev-1"));
        assertFalse(service.claimActive(1L, "dev-1"));
        verify(redisTemplate, org.mockito.Mockito.times(2))
                .expire("coherer:alarm:active:dev:dev-1", Duration.ofSeconds(86400));
    }

    @Test
    void claimActive_redisDown_allowsTrigger() {
        when(setOperations.add(anyString(), any(String[].class)))
                .thenThrow(new RuntimeException("redis down"));

        assertTrue(service.claimActive(1L, "dev-1"));
    }

    @Test
    void releaseActive_removesFromDeviceSet() {
        service.releaseActive(1L, "dev-1");

        verify(setOperations).remove("coherer:alarm:active:dev:dev-1", "1");
    }

    @Test
    void warmUpDevice_writesSetWithTtl() {
        service.warmUpDevice("dev-1", List.of(1L, 2L));

        verify(setOperations).add("coherer:alarm:active:dev:dev-1", "1", "2");
        verify(redisTemplate).expire("coherer:alarm:active:dev:dev-1",
                Duration.ofSeconds(86400));
    }

    @Test
    void warmUp_groupsRecordsByDevice() {
        AlarmRecord first = new AlarmRecord();
        first.setRuleId(1L);
        first.setDeviceId("dev-1");
        AlarmRecord second = new AlarmRecord();
        second.setRuleId(2L);
        second.setDeviceId("dev-1");
        AlarmRecord third = new AlarmRecord();
        third.setRuleId(3L);
        third.setDeviceId("dev-2");

        service.warmUp(List.of(first, second, third));

        verify(setOperations).add("coherer:alarm:active:dev:dev-1", "1", "2");
        verify(setOperations).add("coherer:alarm:active:dev:dev-2", "3");
    }
}
