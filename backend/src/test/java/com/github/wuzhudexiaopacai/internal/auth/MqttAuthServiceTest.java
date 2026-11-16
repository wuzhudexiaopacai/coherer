package com.github.wuzhudexiaopacai.internal.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.github.wuzhudexiaopacai.auth.entity.SysUser;
import com.github.wuzhudexiaopacai.auth.mapper.SysUserMapper;
import com.github.wuzhudexiaopacai.device.mapper.DeviceMapper;
import com.github.wuzhudexiaopacai.host.entity.IotHost;
import com.github.wuzhudexiaopacai.host.service.HostLookupService;
import com.github.wuzhudexiaopacai.security.HmacSigner;
import com.github.wuzhudexiaopacai.security.SecretCipher;
import com.github.wuzhudexiaopacai.tenant.service.TenantLookupService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class MqttAuthServiceTest {

    private static final String WEB_SECRET = "web-secret";
    private static final String INGEST_SECRET = "ingest-secret";

    private MqttAuthService service;

    @BeforeEach
    void setUp() {
        SysUserMapper userMapper = mock(SysUserMapper.class);
        SysUser user = new SysUser();
        user.setId(1L);
        user.setTenantId("default");
        user.setStatus("ACTIVE");
        org.mockito.Mockito.when(userMapper.selectById(1L)).thenReturn(user);
        TenantLookupService tenantLookup = mock(TenantLookupService.class);
        org.mockito.Mockito.when(tenantLookup.isActive("default")).thenReturn(true);
        service = new MqttAuthService(mock(DeviceMapper.class),
                mock(HostLookupService.class), userMapper, tenantLookup,
                mock(SecretCipher.class));
        ReflectionTestUtils.setField(service, "ingestClientId", "coherer-ingest");
        ReflectionTestUtils.setField(service, "ingestUsername", "service");
        ReflectionTestUtils.setField(service, "ingestSecret", INGEST_SECRET);
        ReflectionTestUtils.setField(service, "webSecret", WEB_SECRET);
    }

    @Test
    void authenticate_webCredentialWithValidSignature_allows() {
        String clientId = "web-abc123";
        String username = "web:1:" + (System.currentTimeMillis() + 60_000);
        String password = HmacSigner.signHex(WEB_SECRET, clientId + "|" + username);

        assertTrue(service.authenticate(clientId, username, password));
    }

    @Test
    void authenticate_expiredWebCredential_denies() {
        String clientId = "web-abc123";
        String username = "web:1:" + (System.currentTimeMillis() - 1_000);
        String password = HmacSigner.signHex(WEB_SECRET, clientId + "|" + username);

        assertFalse(service.authenticate(clientId, username, password));
    }

    @Test
    void authenticate_webCredentialWithoutWebClientPrefix_denies() {
        String clientId = "device-1";
        String username = "web:1:" + (System.currentTimeMillis() + 60_000);
        String password = HmacSigner.signHex(WEB_SECRET, clientId + "|" + username);

        assertFalse(service.authenticate(clientId, username, password));
    }

    @Test
    void authenticate_webCredentialWithWrongSignature_denies() {
        String clientId = "web-abc123";
        String username = "web:1:" + (System.currentTimeMillis() + 60_000);

        assertFalse(service.authenticate(clientId, username, "deadbeef"));
    }

    @Test
    void authenticate_ingestServiceAccount_allows() {
        assertTrue(service.authenticate("coherer-ingest", "service", INGEST_SECRET));
        assertFalse(service.authenticate("coherer-ingest", "service", "wrong"));
    }
}
