package com.github.wuzhudexiaopacai.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.wuzhudexiaopacai.ota.entity.OtaRollout;
import com.github.wuzhudexiaopacai.ota.entity.OtaTask;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

/** 真实 PostgreSQL/Redis 上的 OTA 全链路：灰度、重试、回退。 */
class OtaFlowIT extends AbstractIntegrationTest {

    @Test
    void rollout_threeBatches_succeedsSequentially() {
        String productId = createProduct("it-rollout");
        List<String> deviceIds = registerDevices(productId, 3, "it-r");
        String firmwareId = uploadFirmware(productId, "1.2.0",
                "rollout-firmware".getBytes(StandardCharsets.UTF_8));
        String rolloutId = createRollout(firmwareId, deviceIds, 1, 50, 1);

        rolloutService.advance(Long.parseLong(rolloutId));

        for (int batch = 1; batch <= 3; batch++) {
            assertThat(publisher.otaCommands).hasSize(batch);
            RecordingPublisher.OtaCommand command = publisher.otaCommands.get(batch - 1);
            assertThat(command.attempt()).isEqualTo(1);
            reportProgress(command.taskId(), command.deviceId(), "SUCCESS", 100, 1);
            rolloutService.advance(Long.parseLong(rolloutId));
        }

        OtaRollout rollout = rolloutMapper.selectById(Long.parseLong(rolloutId));
        assertThat(rollout.getStatus()).isEqualTo("SUCCESS");
        assertThat(rollout.getSuccessCount()).isEqualTo(3);
        assertThat(rollout.getFailedCount()).isZero();
        assertThat(publisher.otaCommands)
                .extracting(RecordingPublisher.OtaCommand::deviceId)
                .containsExactlyInAnyOrderElementsOf(deviceIds);
        assertThat(publisher.otaCommands)
                .extracting(RecordingPublisher.OtaCommand::version)
                .containsOnly("1.2.0");
    }

    @Test
    void task_failure_withRetry_redispatchesAndSucceeds() {
        String productId = createProduct("it-retry");
        String deviceId = registerDevices(productId, 1, "it-retry").get(0);
        String firmwareId = uploadFirmware(productId, "2.0.0",
                "retry-firmware".getBytes(StandardCharsets.UTF_8));
        String taskId = createTask(deviceId, firmwareId, 2);

        RecordingPublisher.OtaCommand first = publisher.otaCommands.get(0);
        reportProgress(first.taskId(), first.deviceId(), "FAILED", 0, 1);

        OtaTask pending = taskMapper.selectById(Long.parseLong(taskId));
        assertThat(pending.getStatus()).isEqualTo("PENDING");
        assertThat(pending.getAttempt()).isEqualTo(2);
        assertThat(pending.getNextAttemptAt()).isNotNull();

        jdbc.update("UPDATE ota_task SET next_attempt_at = now() - interval '1 second' "
                + "WHERE id = ?", Long.parseLong(taskId));
        otaService.retryDueTasks();

        assertThat(publisher.otaCommands).hasSize(2);
        RecordingPublisher.OtaCommand retry = publisher.otaCommands.get(1);
        assertThat(retry.attempt()).isEqualTo(2);
        reportProgress(retry.taskId(), retry.deviceId(), "SUCCESS", 100, 2);

        OtaTask done = taskMapper.selectById(Long.parseLong(taskId));
        assertThat(done.getStatus()).isEqualTo("SUCCESS");
        assertThat(done.getAttempt()).isEqualTo(2);
    }

    @Test
    void rollback_createsRollbackTaskAndRestoresVersion() {
        String productId = createProduct("it-rollback");
        String deviceId = registerDevices(productId, 1, "it-rb").get(0);
        String oldFirmwareId = uploadFirmware(productId, "1.0.0",
                "old-firmware".getBytes(StandardCharsets.UTF_8));
        String newFirmwareId = uploadFirmware(productId, "1.2.0",
                "new-firmware".getBytes(StandardCharsets.UTF_8));
        jdbc.update("UPDATE device SET firmware_version = '1.0.0' WHERE device_id = ?", deviceId);

        String taskId = createTask(deviceId, newFirmwareId, 1);
        RecordingPublisher.OtaCommand upgrade = publisher.otaCommands.get(0);
        reportProgress(upgrade.taskId(), deviceId, "SUCCESS", 100, 1);

        assertThat(deviceVersion(deviceId)).isEqualTo("1.2.0");
        OtaTask succeeded = taskMapper.selectById(Long.parseLong(taskId));
        assertThat(succeeded.getStatus()).isEqualTo("SUCCESS");
        assertThat(succeeded.getFromVersion()).isEqualTo("1.0.0");

        ResponseEntity<JsonNode> response = rest.exchange(
                "/api/ota/tasks/" + taskId + "/rollback",
                HttpMethod.POST, new HttpEntity<>(authJson()), JsonNode.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode data = response.getBody().path("data");
        assertThat(data.path("taskType").asText()).isEqualTo("ROLLBACK");
        assertThat(data.path("fromVersion").asText()).isEqualTo("1.2.0");
        assertThat(data.path("firmwareVersion").asText()).isEqualTo("1.0.0");

        assertThat(publisher.otaCommands).hasSize(2);
        RecordingPublisher.OtaCommand rollback = publisher.otaCommands.get(1);
        assertThat(rollback.version()).isEqualTo("1.0.0");
        reportProgress(rollback.taskId(), deviceId, "SUCCESS", 100, 1);

        assertThat(deviceVersion(deviceId)).isEqualTo("1.0.0");
        assertThat(oldFirmwareId).isNotBlank();
    }

    private String deviceVersion(String deviceId) {
        return jdbc.queryForObject(
                "SELECT firmware_version FROM device WHERE device_id = ?", String.class, deviceId);
    }
}
