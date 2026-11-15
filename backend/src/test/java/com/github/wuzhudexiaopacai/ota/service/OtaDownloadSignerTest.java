package com.github.wuzhudexiaopacai.ota.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OtaDownloadSignerTest {

    private static final String SECRET = "ota-test-secret-0123456789";

    @Test
    void verify_validSignature_returnsTrue() {
        OtaDownloadSigner signer = new OtaDownloadSigner(SECRET);
        long expires = System.currentTimeMillis() + 60_000;
        String signature = signer.sign(10L, "dev-1", expires);

        assertTrue(signer.verify(10L, "dev-1", expires, signature));
    }

    @Test
    void verify_expired_returnsFalse() {
        OtaDownloadSigner signer = new OtaDownloadSigner(SECRET);
        long expires = System.currentTimeMillis() - 1_000;
        String signature = signer.sign(10L, "dev-1", expires);

        assertFalse(signer.verify(10L, "dev-1", expires, signature));
    }

    @Test
    void verify_otherDeviceOrFirmware_returnsFalse() {
        OtaDownloadSigner signer = new OtaDownloadSigner(SECRET);
        long expires = System.currentTimeMillis() + 60_000;
        String signature = signer.sign(10L, "dev-1", expires);

        assertFalse(signer.verify(10L, "dev-2", expires, signature));
        assertFalse(signer.verify(11L, "dev-1", expires, signature));
    }

    @Test
    void constructor_shortSecret_failsFast() {
        assertThrows(IllegalStateException.class, () -> new OtaDownloadSigner("too-short"));
    }
}
