package com.github.wuzhudexiaopacai.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.wuzhudexiaopacai.common.BizException;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class RefreshTokenStoreTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private RefreshTokenStore store;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        store = new RefreshTokenStore(redisTemplate);
    }

    @Test
    void issue_storesJtiWithTtl() {
        String jti = store.issue("default", "admin", 604800);

        assertEquals(32, jti.length());
        verify(valueOperations).set(eq("coherer:refresh:" + jti), eq("default|admin"),
                eq(Duration.ofSeconds(604800)));
    }

    @Test
    void consume_existingJti_returnsTrueAndDeletes() {
        when(valueOperations.getAndDelete("coherer:refresh:jti-1")).thenReturn("default|admin");

        assertTrue(store.consume("default", "jti-1", "admin"));
    }

    @Test
    void consume_wrongUser_returnsFalse() {
        when(valueOperations.getAndDelete("coherer:refresh:jti-1")).thenReturn("default|admin");

        assertFalse(store.consume("default", "jti-1", "viewer"));
    }

    @Test
    void consume_missingJti_returnsFalse() {
        when(valueOperations.getAndDelete(anyString())).thenReturn(null);

        assertFalse(store.consume("default", "jti-x", "admin"));
    }

    @Test
    void issue_redisDown_throws503() {
        org.mockito.Mockito.doThrow(new RuntimeException("redis down"))
                .when(valueOperations).set(anyString(), anyString(), any(Duration.class));

        BizException exception = assertThrows(BizException.class,
                () -> store.issue("default", "admin", 60));
        assertEquals(503, exception.getCode());
    }

    @Test
    void consume_redisDown_throws503() {
        when(valueOperations.getAndDelete(anyString()))
                .thenThrow(new RuntimeException("redis down"));

        BizException exception = assertThrows(BizException.class,
                () -> store.consume("default", "jti-1", "admin"));
        assertEquals(503, exception.getCode());
    }
}
