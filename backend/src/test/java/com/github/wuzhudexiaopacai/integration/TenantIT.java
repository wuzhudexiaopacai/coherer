package com.github.wuzhudexiaopacai.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.wuzhudexiaopacai.device.service.DeviceLookupService;
import com.github.wuzhudexiaopacai.security.TenantContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * 多租户端到端隔离：开通租户后，认证、项目、产品、空间、设备、主机全部按租户隔离；
 * 平台管理员可以管理租户，普通租户管理员不能跨出租户边界。
 */
class TenantIT extends AbstractIntegrationTest {

    private static final String TENANT_ADMIN_PASSWORD = "Tenant@123";

    @Autowired
    private DeviceLookupService deviceLookupService;

    /**
     * 回归：本地设备缓存的键只有 deviceId，命中时不走 SQL，租户拦截器不生效。
     * 必须先由租户 A 的真实流量预热缓存，再验证租户 B 无法借缓存拿到 A 的设备。
     */
    @Test
    void cachedDeviceIsNotVisibleToAnotherTenant() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String codeA = "ca" + suffix;
        String codeB = "cb" + suffix;
        createTenant(codeA, "缓存租户 A");
        createTenant(codeB, "缓存租户 B");
        String tokenA = login(codeA, "admin", TENANT_ADMIN_PASSWORD);
        String tokenB = login(codeB, "admin", TENANT_ADMIN_PASSWORD);

        String productA = createProduct(tokenA, "cache-product-a");
        String deviceA = registerDevice(tokenA, productA);
        String firmwareA = uploadFirmwareAs(tokenA, productA, "1.0.0");
        String productB = createProduct(tokenB, "cache-product-b");
        String firmwareB = uploadFirmwareAs(tokenB, productB, "1.0.0");

        // 模拟租户 A 的 MQTT 上行：设备行进入本 JVM 的 30 秒本地缓存
        assertThat(TenantContext.callAs(codeA,
                () -> deviceLookupService.getByDeviceId(deviceA))).isNotNull();
        // 同一缓存行对租户 B 必须不可见
        assertThat(TenantContext.callAs(codeB,
                () -> deviceLookupService.getByDeviceId(deviceA))).isNull();

        // 租户 B 通过 REST 下发命令：必须 404，且不能向租户 A 的主题发布
        ResponseEntity<JsonNode> command = exchange(tokenB, HttpMethod.POST,
                "/api/devices/" + deviceA + "/commands",
                Map.of("identifier", "reboot", "params", Map.of()));
        assertThat(command.getStatusCode().value()).isEqualTo(404);
        assertThat(publisher.commands).isEmpty();

        // 读路径同样不可见
        ResponseEntity<JsonNode> telemetry = exchange(tokenB, HttpMethod.GET,
                "/api/devices/" + deviceA + "/telemetry", null);
        assertThat(telemetry.getStatusCode().value()).isEqualTo(404);
        ResponseEntity<JsonNode> shadow = exchange(tokenB, HttpMethod.GET,
                "/api/devices/" + deviceA + "/shadow", null);
        assertThat(shadow.getStatusCode().value()).isEqualTo(404);

        // OTA：即使固件属于租户 B，目标设备仍必须按租户 A 之外不可见处理
        ResponseEntity<JsonNode> ota = exchange(tokenB, HttpMethod.POST, "/api/ota/tasks",
                Map.of("deviceId", deviceA, "firmwareId", firmwareB, "maxAttempts", 1));
        assertThat(ota.getStatusCode().value()).isEqualTo(404);
        assertThat(firmwareA).isNotBlank();
    }

    @Test
    void tenantProvisioningAndDataIsolation() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String codeA = "ta" + suffix;
        String codeB = "tb" + suffix;

        JsonNode tenantA = createTenant(codeA, "租户 A");
        JsonNode tenantB = createTenant(codeB, "租户 B");
        assertThat(tenantA.path("projectCount").asLong()).isEqualTo(1);
        assertThat(tenantB.path("projectCount").asLong()).isEqualTo(1);

        JsonNode tenants = getJson("/api/tenants");
        List<String> tenantIds = new ArrayList<>();
        tenants.path("data").forEach(node -> tenantIds.add(node.path("id").asText()));
        assertThat(tenantIds).contains(codeA, codeB);

        // 同一用户名 admin 可在不同租户中同时存在
        String tokenA = login(codeA, "admin", TENANT_ADMIN_PASSWORD);
        String tokenB = login(codeB, "admin", TENANT_ADMIN_PASSWORD);

        JsonNode meA = getAs(tokenA, "/api/auth/me");
        assertThat(meA.path("data").path("tenantId").asText()).isEqualTo(codeA);
        assertThat(meA.path("data").path("platformAdmin").asBoolean()).isFalse();
        JsonNode meB = getAs(tokenB, "/api/auth/me");
        assertThat(meB.path("data").path("tenantId").asText()).isEqualTo(codeB);

        // 普通租户管理员没有平台租户管理权限
        ResponseEntity<JsonNode> forbidden = exchange(tokenA, HttpMethod.GET,
                "/api/tenants", null);
        assertThat(forbidden.getStatusCode().value()).isEqualTo(403);

        // 每个新租户只有一个默认项目；项目创建后互相不可见
        assertThat(getAs(tokenA, "/api/projects").path("data")).hasSize(1);
        assertThat(getAs(tokenB, "/api/projects").path("data")).hasSize(1);
        Long projectA = createProject(tokenA, "A 专属项目");
        assertThat(projectA).isNotNull();
        assertThat(getAs(tokenB, "/api/projects").path("data")).hasSize(1);
        ResponseEntity<JsonNode> crossProject = exchange(tokenB, HttpMethod.GET,
                "/api/projects/" + projectA, null);
        assertThat(crossProject.getStatusCode().value()).isEqualTo(404);

        // 产品同租户内可见、跨租户不可见
        String productA = createProduct(tokenA, "tenant-product-a");
        String productB = createProduct(tokenB, "tenant-product-b");
        assertThat(getAs(tokenA, "/api/products").path("data").path("records"))
                .hasSize(1);
        ResponseEntity<JsonNode> crossProductA = exchange(tokenA, HttpMethod.GET,
                "/api/products/" + productB, null);
        assertThat(crossProductA.getStatusCode().value()).isEqualTo(404);
        ResponseEntity<JsonNode> crossProductB = exchange(tokenB, HttpMethod.GET,
                "/api/products/" + productA, null);
        assertThat(crossProductB.getStatusCode().value()).isEqualTo(404);

        // 默认空间由租户初始化，设备不传 spaceId 时自动落到本租户默认空间
        String deviceA = registerDevice(tokenA, productA);
        assertThat(deviceA).isNotBlank();
        JsonNode devicesA = getAs(tokenA, "/api/devices");
        JsonNode devicesB = getAs(tokenB, "/api/devices");
        assertThat(devicesA.path("data").path("records")).hasSize(1);
        assertThat(devicesB.path("data").path("records")).hasSize(0);
        long deviceRowId = devicesA.path("data").path("records").get(0).path("id").asLong();
        ResponseEntity<JsonNode> crossDevice = exchange(tokenB, HttpMethod.GET,
                "/api/devices/" + deviceRowId, null);
        assertThat(crossDevice.getStatusCode().value()).isEqualTo(404);

        // 主机与设备同样隔离
        String hostA = registerHost(tokenA);
        assertThat(hostA).isNotBlank();
        assertThat(getAs(tokenA, "/api/hosts").path("data").path("records")).hasSize(1);
        assertThat(getAs(tokenB, "/api/hosts").path("data").path("records")).hasSize(0);

        // 空间树包含默认空间；跨租户不可见
        JsonNode spacesA = getAs(tokenA, "/api/spaces/tree");
        JsonNode spacesB = getAs(tokenB, "/api/spaces/tree");
        assertThat(spacesA.path("data")).hasSize(1);
        assertThat(spacesB.path("data")).hasSize(1);
        assertThat(spacesA.path("data").get(0).path("id").asLong())
                .isNotEqualTo(spacesB.path("data").get(0).path("id").asLong());

        // 默认项目不可删除
        Long defaultProjectA = getAs(tokenA, "/api/projects").path("data").get(0)
                .path("id").asLong();
        ResponseEntity<JsonNode> deleteDefault = exchange(tokenA, HttpMethod.DELETE,
                "/api/projects/" + defaultProjectA, null);
        assertThat(deleteDefault.getStatusCode().value()).isEqualTo(409);
    }

    @Test
    void disabledTenantRejectsLoginAndExistingToken() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String code = "td" + suffix;
        createTenant(code, "待禁用租户");
        String token = login(code, "admin", TENANT_ADMIN_PASSWORD);

        JsonNode disabled = updateTenant(code, Map.of("status", "DISABLED"));
        assertThat(disabled.path("status").asText()).isEqualTo("DISABLED");

        ResponseEntity<JsonNode> login = rest.postForEntity("/api/auth/login",
                Map.of("tenantCode", code, "username", "admin",
                        "password", TENANT_ADMIN_PASSWORD), JsonNode.class);
        assertThat(login.getStatusCode().value()).isEqualTo(403);
        assertThat(login.getBody().path("code").asInt()).isEqualTo(403);

        ResponseEntity<JsonNode> existing = exchange(token, HttpMethod.GET,
                "/api/projects", null);
        assertThat(existing.getStatusCode().value()).isEqualTo(401);
    }

    private JsonNode createTenant(String code, String name) {
        ResponseEntity<JsonNode> response = exchange(token(), HttpMethod.POST, "/api/tenants",
                Map.of(
                        "code", code,
                        "name", name,
                        "plan", "STANDARD",
                        "adminUsername", "admin",
                        "adminPassword", TENANT_ADMIN_PASSWORD,
                        "adminNickname", name + "管理员"));
        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("create tenant -> %s %s", response.getStatusCode(), response.getBody())
                .isTrue();
        return response.getBody().path("data");
    }

    private JsonNode updateTenant(String code, Map<String, Object> body) {
        ResponseEntity<JsonNode> response = exchange(token(), HttpMethod.PUT,
                "/api/tenants/" + code, body);
        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("update tenant -> %s %s", response.getStatusCode(), response.getBody())
                .isTrue();
        return response.getBody().path("data");
    }

    private String login(String tenantCode, String username, String password) {
        ResponseEntity<JsonNode> response = rest.postForEntity("/api/auth/login",
                Map.of("tenantCode", tenantCode, "username", username, "password", password),
                JsonNode.class);
        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("login %s/%s -> %s %s", tenantCode, username,
                        response.getStatusCode(), response.getBody())
                .isTrue();
        String accessToken = response.getBody().path("data").path("accessToken").asText();
        assertThat(accessToken).isNotBlank();
        return accessToken;
    }

    private JsonNode getAs(String token, String path) {
        ResponseEntity<JsonNode> response = exchange(token, HttpMethod.GET, path, null);
        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("GET %s -> %s %s", path, response.getStatusCode(), response.getBody())
                .isTrue();
        return response.getBody();
    }

    private ResponseEntity<JsonNode> exchange(String token, HttpMethod method,
                                              String path, Object body) {
        return rest.exchange(path, method, new HttpEntity<>(body, bearer(token)), JsonNode.class);
    }

    private Long createProject(String token, String name) {
        ResponseEntity<JsonNode> response = exchange(token, HttpMethod.POST, "/api/projects",
                Map.of("name", name));
        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("create project -> %s %s", response.getStatusCode(), response.getBody())
                .isTrue();
        return response.getBody().path("data").path("id").asLong();
    }

    private String createProduct(String token, String name) {
        ResponseEntity<JsonNode> response = exchange(token, HttpMethod.POST, "/api/products",
                Map.of("name", name));
        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("create product -> %s %s", response.getStatusCode(), response.getBody())
                .isTrue();
        return response.getBody().path("data").path("id").asText();
    }

    private String registerDevice(String token, String productId) {
        ResponseEntity<JsonNode> response = exchange(token, HttpMethod.POST, "/api/devices",
                Map.of("productId", Long.valueOf(productId),
                        "count", 1, "namePrefix", "tenant-it"));
        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("register device -> %s %s", response.getStatusCode(), response.getBody())
                .isTrue();
        return response.getBody().path("data").get(0).path("deviceId").asText();
    }

    private String registerHost(String token) {
        ResponseEntity<JsonNode> response = exchange(token, HttpMethod.POST, "/api/hosts",
                Map.of("name", "tenant-it-host-" + UUID.randomUUID().toString().substring(0, 6)));
        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("register host -> %s %s", response.getStatusCode(), response.getBody())
                .isTrue();
        return response.getBody().path("data").path("hostId").asText();
    }

    private String uploadFirmwareAs(String token, String productId, String version) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource("tenant-it-firmware".getBytes()) {
            @Override
            public String getFilename() {
                return "firmware.bin";
            }
        });
        HttpHeaders headers = bearer(token);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<JsonNode> response = rest.exchange(
                "/api/ota/firmwares?productId=" + productId + "&version=" + version,
                HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("upload firmware -> %s %s", response.getStatusCode(), response.getBody())
                .isTrue();
        return response.getBody().path("data").path("id").asText();
    }
}
