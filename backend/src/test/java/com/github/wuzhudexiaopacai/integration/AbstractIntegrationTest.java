package com.github.wuzhudexiaopacai.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wuzhudexiaopacai.ota.mapper.OtaRolloutMapper;
import com.github.wuzhudexiaopacai.ota.mapper.OtaTaskMapper;
import com.github.wuzhudexiaopacai.ota.service.FirmwareService;
import com.github.wuzhudexiaopacai.ota.service.OtaProgressService;
import com.github.wuzhudexiaopacai.ota.service.OtaRolloutService;
import com.github.wuzhudexiaopacai.ota.service.OtaService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * 集成测试基类：连接 deploy/docker-compose.test.yml 提供的真实 PostgreSQL/Redis，
 * 跑完整 Flyway；MQTT 用录制型 Publisher。TDengine/EMQX 不在本组范围内。
 *
 * <p>本地执行：deploy/scripts/integration-test.sh；CI 由 workflow 启动同一套测试容器。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "coherer.mqtt.enabled=false",
        "coherer.ota.storage-type=local",
        "coherer.ota.retry-backoff-seconds=1",
        "coherer.device.online-sweep-interval-ms=3600000",
        "coherer.device.online-sweep-initial-delay-ms=3600000",
        "coherer.host.online-sweep-interval-ms=3600000",
        "coherer.host.online-sweep-initial-delay-ms=3600000",
        "coherer.ota.timeout-sweep-interval-ms=3600000",
        "coherer.ota.retry-sweep-interval-ms=3600000",
        "coherer.ota.retry-sweep-initial-delay-ms=3600000",
        "coherer.ota.rollout-sweep-interval-ms=3600000",
        "coherer.ota.rollout-sweep-initial-delay-ms=3600000"
})
@Import(RecordingPublisherConfig.class)
public abstract class AbstractIntegrationTest {

    protected static final String ADMIN_PASSWORD = "ItAdmin@123";
    private static final Path FIRMWARE_DIR = createTempDir();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> env(
                "IT_DB_URL",
                "jdbc:postgresql://localhost:55432/coherer?stringtype=unspecified"));
        registry.add("spring.datasource.username", () -> env("IT_DB_USER", "coherer"));
        registry.add("spring.datasource.password", () -> env("IT_DB_PASSWORD", "coherer-it-pass"));
        registry.add("spring.data.redis.host", () -> env("IT_REDIS_HOST", "localhost"));
        registry.add("spring.data.redis.port", () -> env("IT_REDIS_PORT", "56379"));
        registry.add("spring.data.redis.password", () -> env("IT_REDIS_PASSWORD", "coherer-it-pass"));

        registry.add("coherer.security.secret-key", () -> Base64.getEncoder()
                .encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)));
        registry.add("coherer.security.jwt-secret", () -> "it-jwt-secret-0123456789abcdef0123456789");
        registry.add("coherer.security.internal-token", () -> "it-internal-token");
        registry.add("coherer.security.admin-password", () -> ADMIN_PASSWORD);
        registry.add("coherer.mqtt.ingest.secret", () -> "it-ingest-secret");
        registry.add("coherer.mqtt.web.secret", () -> "it-web-secret");
        registry.add("coherer.ota.secret", () -> "it-ota-download-secret");
        registry.add("coherer.ota.firmware-dir", FIRMWARE_DIR::toString);
        registry.add("coherer.ota.public-base-url", () -> "https://localhost:8443");
        registry.add("coherer.tdengine.url", () -> "jdbc:TAOS-WS://127.0.0.1:1");
        registry.add("coherer.tdengine.username", () -> "root");
        registry.add("coherer.tdengine.password", () -> "it-td-pass");
    }

    @Autowired
    protected TestRestTemplate rest;
    @Autowired
    protected ObjectMapper objectMapper;
    @Autowired
    protected RecordingPublisher publisher;
    @Autowired
    protected OtaService otaService;
    @Autowired
    protected OtaRolloutService rolloutService;
    @Autowired
    protected OtaProgressService progressService;
    @Autowired
    protected FirmwareService firmwareService;
    @Autowired
    protected OtaTaskMapper taskMapper;
    @Autowired
    protected OtaRolloutMapper rolloutMapper;
    @Autowired
    protected DataSource dataSource;
    protected JdbcTemplate jdbc;

    @MockBean(name = "tdengineJdbcTemplate")
    private JdbcTemplate tdengineJdbcTemplate;

    private String token;

    @BeforeEach
    void resetPublisher() {
        jdbc = new JdbcTemplate(dataSource);
        publisher.clear();
    }

    protected String token() {
        if (token != null) {
            return token;
        }
        Map<?, ?> response = rest.postForObject("/api/auth/login",
                Map.of("username", "admin", "password", ADMIN_PASSWORD), Map.class);
        Map<?, ?> data = (Map<?, ?>) response.get("data");
        token = String.valueOf(data.get("accessToken"));
        assertThat(token).isNotBlank();
        return token;
    }

    protected HttpHeaders authJson() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token());
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    protected JsonNode postJson(String path, Object body) {
        ResponseEntity<JsonNode> response = rest.exchange(path, org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(body, authJson()), JsonNode.class);
        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("POST %s -> %s %s", path, response.getStatusCode(), response.getBody())
                .isTrue();
        return response.getBody();
    }

    protected JsonNode getJson(String path) {
        ResponseEntity<JsonNode> response = rest.exchange(path,
                org.springframework.http.HttpMethod.GET, new HttpEntity<>(authJson()),
                JsonNode.class);
        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("GET %s -> %s %s", path, response.getStatusCode(), response.getBody())
                .isTrue();
        return response.getBody();
    }

    protected String createProduct(String prefix) {
        JsonNode body = postJson("/api/products", Map.of(
                "name", prefix + "-" + UUID.randomUUID().toString().substring(0, 8),
                "category", "IT",
                "nodeType", "DIRECT"));
        return body.path("data").path("id").asText();
    }

    protected List<String> registerDevices(String productId, int count, String prefix) {
        JsonNode body = postJson("/api/devices", Map.of(
                "productId", productId,
                "count", count,
                "namePrefix", prefix));
        List<String> deviceIds = new ArrayList<>();
        body.path("data").forEach(item -> deviceIds.add(item.path("deviceId").asText()));
        assertThat(deviceIds).hasSize(count);
        return deviceIds;
    }

    protected String uploadFirmware(String productId, String version, byte[] content) {
        try {
            Path file = Files.createTempFile("coherer-it-fw-", ".bin");
            Files.write(file, content);
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("file", new FileSystemResource(file));
            HttpHeaders headers = authJson();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);
            ResponseEntity<JsonNode> response = rest.exchange(
                    "/api/ota/firmwares?productId=" + productId + "&version=" + version,
                    org.springframework.http.HttpMethod.POST,
                    new HttpEntity<>(body, headers), JsonNode.class);
            assertThat(response.getStatusCode().is2xxSuccessful())
                    .as("upload firmware -> %s %s", response.getStatusCode(), response.getBody())
                    .isTrue();
            return response.getBody().path("data").path("id").asText();
        } catch (Exception e) {
            throw new IllegalStateException("上传测试固件失败", e);
        }
    }

    protected String createTask(String deviceId, String firmwareId, int maxAttempts) {
        JsonNode body = postJson("/api/ota/tasks", Map.of(
                "deviceId", deviceId,
                "firmwareId", firmwareId,
                "maxAttempts", maxAttempts));
        return body.path("data").path("id").asText();
    }

    protected String createRollout(String firmwareId, List<String> deviceIds, int batchSize,
                                   int threshold, int maxAttempts) {
        JsonNode body = postJson("/api/ota/rollouts", Map.of(
                "firmwareId", firmwareId,
                "name", "it-rollout-" + UUID.randomUUID().toString().substring(0, 6),
                "targetType", "DEVICE",
                "deviceIds", deviceIds,
                "batchSize", batchSize,
                "failureThresholdPercent", threshold,
                "maxAttempts", maxAttempts,
                "autoAdvance", true));
        return body.path("data").path("id").asText();
    }

    protected void reportProgress(long taskId, String deviceId, String status, int progress,
                                  int attempt) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("taskId", taskId);
            payload.put("status", status);
            payload.put("progress", progress);
            payload.put("attempt", attempt);
            if ("FAILED".equals(status)) {
                payload.put("error", "integration test failure");
            }
            progressService.onProgress(deviceId, objectMapper.writeValueAsBytes(payload));
        } catch (Exception e) {
            throw new IllegalStateException("上报测试进度失败", e);
        }
    }

    private static Path createTempDir() {
        try {
            return Files.createTempDirectory("coherer-it-firmware-");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
