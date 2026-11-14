package com.github.wuzhudexiaopacai.audit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.wuzhudexiaopacai.audit.entity.AuditLog;
import com.github.wuzhudexiaopacai.audit.mapper.AuditLogMapper;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class AuditLogServiceTest {

    private AuditLogMapper mapper;
    private AuditLogService service;

    @BeforeEach
    void setUp() {
        mapper = mock(AuditLogMapper.class);
        service = new AuditLogService();
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
    }

    @Test
    void recordAs_persistsAuditEntry() {
        service.recordAs("admin", "127.0.0.1", "LOGIN_SUCCESS", "auth",
                Map.of("role", "ADMIN"));

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(mapper).insert(captor.capture());
        AuditLog entry = captor.getValue();
        assertEquals("admin", entry.getUsername());
        assertEquals("127.0.0.1", entry.getIp());
        assertEquals("LOGIN_SUCCESS", entry.getAction());
        assertEquals("auth", entry.getResource());
        assertEquals("ADMIN", entry.getDetail().get("role"));
        assertEquals("default", entry.getTenantId());
    }

    @Test
    void recordAs_doesNotThrow_whenPersistenceFails() {
        when(mapper.insert(any(AuditLog.class))).thenThrow(new RuntimeException("db down"));

        assertDoesNotThrow(() -> service.recordAs("admin", "127.0.0.1",
                "DEVICE_REGISTER", "device", Map.of()));
    }
}
