package com.github.wuzhudexiaopacai.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.wuzhudexiaopacai.telemetry.service.TelemetryIdempotencyService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class TelemetryIdempotencyServiceTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private TelemetryIdempotencyService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        service = new TelemetryIdempotencyService(redisTemplate, 600);
    }

    @Test
    void sameMessageId_producesSameKey() {
        String first = service.key("dev-1", "42", 1000L, Map.of("t", 1));
        String second = service.key("dev-1", "42", 2000L, Map.of("t", 2));

        assertEquals(first, second);
    }

    @Test
    void differentDevices_produceDifferentKeys() {
        assertNotEquals(
                service.key("dev-1", "42", 1L, Map.of()),
                service.key("dev-2", "42", 1L, Map.of()));
    }

    @Test
    void withoutMessageId_usesTimestampAndParamsFingerprint() {
        String first = service.key("dev-1", "", 1000L, Map.of("a", 1, "b", 2));
        String reordered = service.key("dev-1", null, 1000L, Map.of("b", 2, "a", 1));
        String otherTs = service.key("dev-1", "", 1001L, Map.of("a", 1, "b", 2));

        assertEquals(first, reordered);
        assertNotEquals(first, otherTs);
    }

    @Test
    void markIfNew_returnsTrueOnlyForFirstWrite() {
        when(valueOperations.setIfAbsent(anyString(), eq("1"), any(Duration.class)))
                .thenReturn(true, false);

        assertTrue(service.markIfNew("k"));
        assertFalse(service.markIfNew("k"));
    }

    @Test
    void markIfNew_failsOpenWhenRedisUnavailable() {
        when(valueOperations.setIfAbsent(anyString(), eq("1"), any(Duration.class)))
                .thenThrow(new RuntimeException("redis down"));

        // fail-open：宁可重复处理也不能丢数据
        assertTrue(service.markIfNew("k"));
    }

    @Test
    void release_deletesKey() {
        service.release("k");

        verify(redisTemplate).delete("k");
    }

    @Test
    void markIfNew_usesConfiguredTtl() {
        when(valueOperations.setIfAbsent(anyString(), eq("1"), any(Duration.class)))
                .thenReturn(true);

        service.markIfNew("k");

        verify(valueOperations).setIfAbsent("k", "1", Duration.ofSeconds(600));
    }
}
