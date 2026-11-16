package com.github.wuzhudexiaopacai.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.wuzhudexiaopacai.ota.entity.Firmware;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

/** 真实文件存储 + Spring MVC 的签名下载与 Range 断点续传。 */
class FirmwareDownloadIT extends AbstractIntegrationTest {

    @Test
    void signedDownload_supportsRangeAndRejectsInvalidRange() {
        String productId = createProduct("it-download");
        String deviceId = registerDevices(productId, 1, "it-dl").get(0);
        byte[] content = new byte[1024 * 1024];
        new Random(42).nextBytes(content);
        String firmwareId = uploadFirmware(productId, "3.0.0", content);

        Firmware firmware = asDefault(() -> firmwareService.require(Long.parseLong(firmwareId)));
        URI uri = URI.create(firmwareService.downloadUrl(firmware, deviceId));
        String path = uri.getRawPath() + "?" + uri.getRawQuery();

        HttpHeaders rangeHeaders = new HttpHeaders();
        rangeHeaders.set(HttpHeaders.RANGE, "bytes=100-199");
        ResponseEntity<byte[]> partial = rest.exchange(path, HttpMethod.GET,
                new HttpEntity<>(rangeHeaders), byte[].class);

        assertThat(partial.getStatusCode().value()).isEqualTo(206);
        assertThat(partial.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE))
                .isEqualTo("bytes 100-199/" + content.length);
        assertThat(partial.getHeaders().getFirst(HttpHeaders.ACCEPT_RANGES)).isEqualTo("bytes");
        assertThat(partial.getBody()).hasSize(100);
        assertThat(partial.getBody()).isEqualTo(
                Arrays.copyOfRange(content, 100, 200));

        HttpHeaders badRange = new HttpHeaders();
        badRange.set(HttpHeaders.RANGE, "bytes=" + content.length + "-");
        ResponseEntity<byte[]> invalid = rest.exchange(path, HttpMethod.GET,
                new HttpEntity<>(badRange), byte[].class);
        assertThat(invalid.getStatusCode().value()).isEqualTo(416);
        assertThat(invalid.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE))
                .isEqualTo("bytes */" + content.length);

        ResponseEntity<byte[]> full = rest.exchange(path, HttpMethod.GET,
                HttpEntity.EMPTY, byte[].class);
        assertThat(full.getStatusCode().value()).isEqualTo(200);
        assertThat(full.getBody()).isEqualTo(content);
    }

    @Test
    void rollout_createRejectsDeviceFromAnotherProduct() {
        String productA = createProduct("it-a");
        String productB = createProduct("it-b");
        String deviceB = registerDevices(productB, 1, "it-b").get(0);
        String firmwareId = uploadFirmware(productA, "1.0.0",
                "firmware-a".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        String path = "/api/ota/rollouts";
        var response = rest.exchange(path, HttpMethod.POST,
                new HttpEntity<>(java.util.Map.of(
                        "firmwareId", firmwareId,
                        "name", "cross-product",
                        "targetType", "DEVICE",
                        "deviceIds", List.of(deviceB),
                        "batchSize", 1,
                        "failureThresholdPercent", 20,
                        "maxAttempts", 1,
                        "autoAdvance", true), authJson()),
                com.fasterxml.jackson.databind.JsonNode.class);
        assertThat(response.getStatusCode().value()).isEqualTo(409);
    }
}
