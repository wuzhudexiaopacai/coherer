package com.github.wuzhudexiaopacai.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

/** 项目 → 空间 → 主机/设备的真实数据库隔离测试。 */
class ProjectIT extends AbstractIntegrationTest {

    @Test
    void projectIsolatesSpacesHostsAndDevices() {
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        String projectA = createProject("proj-a-" + suffix);
        String projectB = createProject("proj-b-" + suffix);
        String spaceA = createSpace(projectA, "A 区");
        String spaceB = createSpace(projectB, "B 区");

        String productId = createProduct("it-project");
        createDevice(productId, spaceA);
        createDevice(productId, spaceB);
        createHost(spaceA);
        createHost(spaceB);

        JsonNode devicesA = getJson("/api/devices?current=1&size=20&projectId=" + projectA);
        JsonNode devicesB = getJson("/api/devices?current=1&size=20&projectId=" + projectB);
        assertThat(devicesA.path("data").path("records")).hasSize(1);
        assertThat(devicesB.path("data").path("records")).hasSize(1);
        assertThat(devicesA.path("data").path("records").get(0).path("projectId").asText())
                .isEqualTo(projectA);

        JsonNode hostsA = getJson("/api/hosts?current=1&size=20&projectId=" + projectA);
        JsonNode hostsB = getJson("/api/hosts?current=1&size=20&projectId=" + projectB);
        assertThat(hostsA.path("data").path("records")).hasSize(1);
        assertThat(hostsB.path("data").path("records")).hasSize(1);

        JsonNode overviewA = getJson("/api/dashboard/overview?projectId=" + projectA);
        assertThat(overviewA.path("data").path("deviceTotal").asInt()).isEqualTo(1);
        assertThat(overviewA.path("data").path("productTotal").asInt()).isEqualTo(1);

        // 项目下仍有空间时禁止删除
        ResponseEntity<JsonNode> blocked = rest.exchange("/api/projects/" + projectA,
                HttpMethod.DELETE, new HttpEntity<>(authJson()), JsonNode.class);
        assertThat(blocked.getStatusCode().value()).isEqualTo(409);

        String emptyProject = createProject("empty-" + suffix);
        ResponseEntity<JsonNode> deleted = rest.exchange("/api/projects/" + emptyProject,
                HttpMethod.DELETE, new HttpEntity<>(authJson()), JsonNode.class);
        assertThat(deleted.getStatusCode().is2xxSuccessful()).isTrue();
    }

    @Test
    void restrictedUserSeesOnlyProjectsContainingVisibleSpaces() {
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        String projectA = createProject("restricted-a-" + suffix);
        String projectB = createProject("restricted-b-" + suffix);
        String spaceA = createSpace(projectA, "受限 A 区");
        createSpace(projectB, "受限 B 区");

        String username = "viewer_" + suffix;
        postJson("/api/users", java.util.Map.of(
                "username", username,
                "password", "Viewer@123",
                "nickname", "受限用户",
                "role", "VIEWER",
                "spaceId", spaceA));

        // 用管理员 token 创建用户后，直接用登录接口拿受限用户 token
        ResponseEntity<JsonNode> login = rest.postForEntity("/api/auth/login",
                java.util.Map.of("tenantCode", "default", "username", username,
                        "password", "Viewer@123"), JsonNode.class);
        String viewerToken = login.getBody().path("data").path("accessToken").asText();
        assertThat(viewerToken).isNotBlank();

        ResponseEntity<JsonNode> projects = rest.exchange("/api/projects",
                HttpMethod.GET, new HttpEntity<>(bearer(viewerToken)), JsonNode.class);
        List<String> ids = new ArrayList<>();
        projects.getBody().path("data").forEach(node -> ids.add(node.path("id").asText()));
        assertThat(ids).contains(projectA).doesNotContain(projectB);
    }

    private String createProject(String name) {
        JsonNode body = postJson("/api/projects", java.util.Map.of("name", name));
        return body.path("data").path("id").asText();
    }

    private String createSpace(String projectId, String name) {
        JsonNode body = postJson("/api/spaces", java.util.Map.of(
                "projectId", projectId,
                "name", name,
                "type", "GENERIC"));
        return body.path("data").path("id").asText();
    }

    private String createDevice(String productId, String spaceId) {
        JsonNode body = postJson("/api/devices", java.util.Map.of(
                "productId", productId,
                "count", 1,
                "namePrefix", "it-p",
                "spaceId", spaceId));
        return body.path("data").get(0).path("deviceId").asText();
    }

    private String createHost(String spaceId) {
        JsonNode body = postJson("/api/hosts", java.util.Map.of(
                "name", "it-host-" + UUID.randomUUID().toString().substring(0, 6),
                "spaceId", spaceId));
        return body.path("data").path("hostId").asText();
    }
}
