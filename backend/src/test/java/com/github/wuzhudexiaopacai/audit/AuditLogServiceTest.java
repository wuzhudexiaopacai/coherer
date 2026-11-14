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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class AuditLogServiceTest {

    private AuditLogMapper mapper;
    private AuditLogService service;

    @BeforeEach
    void setUp() {
        mapper = mock(AuditLogMapper.class);
        service = new AuditLogService(null);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void recordAs_persistsAuditEntry() {
        service.recordAs("admin", "127.0.0.1", "LOGIN_SUCCESS", "auth",
                Map.of("role", "ADMIN"));

        AuditLog entry = capture();
        assertEquals("admin", entry.getUsername());
        assertEquals("127.0.0.1", entry.getIp());
        assertEquals("LOGIN_SUCCESS", entry.getAction());
        assertEquals("auth", entry.getResource());
        assertEquals("ADMIN", entry.getDetail().get("role"));
        assertEquals("default", entry.getTenantId());
        assertEquals("SUCCESS", entry.getResult());
    }

    @Test
    void recordAsFailure_marksFailureResult() {
        service.recordAsFailure("admin", "127.0.0.1", "LOGIN_FAILURE", "auth",
                Map.of("reason", "bad_credentials"));

        assertEquals("FAILURE", capture().getResult());
    }

    @Test
    void recordAs_doesNotThrow_whenPersistenceFails() {
        when(mapper.insert(any(AuditLog.class))).thenThrow(new RuntimeException("db down"));

        assertDoesNotThrow(() -> service.recordAs("admin", "127.0.0.1",
                "DEVICE_REGISTER", "device", Map.of()));
    }

    @Test
    void record_fromRequest_capturesUserAgentAndForwardedIp() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/devices");
        request.setRemoteAddr("10.0.0.9");
        request.addHeader("X-Forwarded-For", "203.0.113.7, 10.0.0.9");
        request.addHeader("User-Agent", "CohererTest/1.0");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        service.record("DEVICE_REGISTER", "device", Map.of("count", 2));

        AuditLog entry = capture();
        // 反向代理后必须取真实客户端 IP，而不是网关地址
        assertEquals("203.0.113.7", entry.getIp());
        assertEquals("CohererTest/1.0", entry.getUserAgent());
    }

    @Test
    void record_withoutForwardedHeader_usesRemoteAddr() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/devices");
        request.setRemoteAddr("192.168.1.20");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        service.record("DEVICE_REGISTER", "device", Map.of());

        assertEquals("192.168.1.20", capture().getIp());
    }

    @Test
    void record_truncatesLongUserAgent() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/devices");
        request.addHeader("User-Agent", "x".repeat(500));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        service.record("DEVICE_REGISTER", "device", Map.of());

        assertEquals(256, capture().getUserAgent().length());
    }

    private AuditLog capture() {
        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(mapper).insert(captor.capture());
        return captor.getValue();
    }
}
