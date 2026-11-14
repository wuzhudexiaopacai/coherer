package com.github.wuzhudexiaopacai.mqtt;

import java.io.FileInputStream;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

/** 使用本地 CA 构建 Paho 的 TLS SocketFactory（后端 → EMQX :8883）。 */
public final class MqttTlsSupport {

    private MqttTlsSupport() {
    }

    public static SSLSocketFactory socketFactory(String caFile) {
        try (InputStream in = new FileInputStream(caFile)) {
            CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
            Certificate ca = certificateFactory.generateCertificate(in);

            KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
            trustStore.load(null, null);
            trustStore.setCertificateEntry("coherer-ca", ca);

            TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init(trustStore);

            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trustManagerFactory.getTrustManagers(), null);
            return context.getSocketFactory();
        } catch (Exception e) {
            throw new IllegalStateException("加载 MQTT CA 证书失败: " + caFile, e);
        }
    }
}
