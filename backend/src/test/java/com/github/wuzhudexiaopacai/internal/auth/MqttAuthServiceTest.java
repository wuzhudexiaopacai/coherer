package com.github.wuzhudexiaopacai.internal.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.github.wuzhudexiaopacai.device.mapper.DeviceMapper;
import com.github.wuzhudexiaopacai.security.HmacSigner;
import com.github.wuzhudexiaopacai.security.SecretCipher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class MqttAuthServiceTest {

    private static final String WEB_SECRET = "web-secret";
    private static final String INGEST_SECRET = "ingest-secret";

    private MqttAuthService service;

    @BeforeEach
    void setUp() {
        service = new MqttAuthService(mock(DeviceMapper.class), mock(SecretCipher.class));
        ReflectionTestUtils.setField(service, "ingestClientId", "coherer-ingest");
        ReflectionTestUtils.setField(service, "ingestUsername", "service");
        ReflectionTestUtils.setField(service, "ingestSecret", INGEST_SECRET);
        ReflectionTestUtils.setField(service, "webSecret", WEB_SECRET);
    }

    @Test
    void authenticate_webCredentialWithValidSignature_allows() {
        String clientId = "web-abc123";
        String username = "web:" + (System.currentTimeMillis() + 60_000);
        String password = HmacSigner.signHex(WEB_SECRET, clientId + "|" + username);

        assertTrue(service.authenticate(clientId, username, password));
    }

    @Test
    void authenticate_expiredWebCredential_denies() {
        String clientId = "web-abc123";
        String username = "web:" + (System.currentTimeMillis() - 1_000);
        String password = HmacSigner.signHex(WEB_SECRET, clientId + "|" + username);

        assertFalse(service.authenticate(clientId, username, password));
    }

    @Test
    void authenticate_webCredentialWithoutWebClientPrefix_denies() {
        String clientId = "device-1";
        String username = "web:" + (System.currentTimeMillis() + 60_000);
        String password = HmacSigner.signHex(WEB_SECRET, clientId + "|" + username);

        assertFalse(service.authenticate(clientId, username, password));
    }

    @Test
    void authenticate_webCredentialWithWrongSignature_denies() {
        String clientId = "web-abc123";
        String username = "web:" + (System.currentTimeMillis() + 60_000);

        assertFalse(service.authenticate(clientId, username, "deadbeef"));
    }

    @Test
    void authenticate_ingestServiceAccount_allows() {
        assertTrue(service.authenticate("coherer-ingest", "service", INGEST_SECRET));
        assertFalse(service.authenticate("coherer-ingest", "service", "wrong"));
    }
}
