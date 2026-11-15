package com.github.wuzhudexiaopacai.ota.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wuzhudexiaopacai.ota.entity.OtaTask;
import com.github.wuzhudexiaopacai.ota.event.OtaTaskFinishedEvent;
import com.github.wuzhudexiaopacai.ota.mapper.OtaTaskMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.OffsetDateTime;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/** 处理设备 OTA 进度上报；失败时按 attempt/maxAttempts 决定重试或终态。 */
@Service
@RequiredArgsConstructor
public class OtaProgressService {

    private static final Logger log = LoggerFactory.getLogger(OtaProgressService.class);

    private final OtaTaskMapper taskMapper;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final MeterRegistry meterRegistry;

    @Value("${coherer.ota.retry-backoff-seconds:30}")
    private long retryBackoffSeconds;

    public void onProgress(String deviceId, byte[] payload) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            long taskId = root.path("taskId").asLong(0);
            if (taskId <= 0) {
                log.debug("OTA 进度缺少 taskId deviceId={}", deviceId);
                return;
            }
            OtaTask task = taskMapper.selectById(taskId);
            if (task == null || !deviceId.equals(task.getDeviceId())) {
                log.warn("OTA 进度任务不存在或设备不匹配 deviceId={} taskId={}", deviceId, taskId);
                return;
            }
            if (isTerminal(task.getStatus())) {
                return;
            }
            // 设备回传的 attempt 必须与当前尝试一致，避免旧尝试的 QoS1 重复消息推进重试次数
            int reportedAttempt = root.path("attempt").asInt(0);
            int currentAttempt = task.getAttempt() == null ? 1 : task.getAttempt();
            if (reportedAttempt > 0 && reportedAttempt != currentAttempt) {
                log.debug("忽略过期 OTA 进度 deviceId={} taskId={} reported={} current={}",
                        deviceId, taskId, reportedAttempt, currentAttempt);
                return;
            }
            // 已进入重试退避窗口，旧连接的迟到回执不应再次触发失败
            if ("PENDING".equals(task.getStatus()) && task.getNextAttemptAt() != null) {
                return;
            }

            String status = root.path("status").asText("").toUpperCase();
            int progress = Math.min(Math.max(root.path("progress").asInt(task.getProgress() == null
                    ? 0 : task.getProgress()), 0), 100);
            if ("FAILED".equals(status)) {
                String error = root.path("error").asText("设备升级失败");
                handleFailure(task, truncate(error), progress);
                return;
            }
            OffsetDateTime now = OffsetDateTime.now();
            if ("SUCCESS".equals(status) || progress >= 100) {
                int updated = taskMapper.update(null, Wrappers.<OtaTask>lambdaUpdate()
                        .eq(OtaTask::getId, task.getId())
                        .eq(OtaTask::getAttempt, currentAttempt)
                        .in(OtaTask::getStatus, java.util.List.of(
                                "PENDING", "SENT", "DOWNLOADING"))
                        .set(OtaTask::getStatus, "SUCCESS")
                        .set(OtaTask::getProgress, 100)
                        .set(OtaTask::getError, null)
                        .set(OtaTask::getNextAttemptAt, null)
                        .set(OtaTask::getUpdatedAt, now)
                        .set(OtaTask::getFinishedAt, now));
                if (updated == 0) {
                    return;
                }
                task.setStatus("SUCCESS");
                task.setProgress(100);
                task.setError(null);
                task.setNextAttemptAt(null);
                task.setFinishedAt(now);
                task.setUpdatedAt(now);
                meterRegistry.counter("coherer.ota.finished", "result", "success").increment();
                publishFinished(task);
            } else if ("DOWNLOADING".equals(status)) {
                int updated = taskMapper.update(null, Wrappers.<OtaTask>lambdaUpdate()
                        .eq(OtaTask::getId, task.getId())
                        .eq(OtaTask::getAttempt, currentAttempt)
                        .in(OtaTask::getStatus, java.util.List.of(
                                "PENDING", "SENT", "DOWNLOADING"))
                        .set(OtaTask::getStatus, "DOWNLOADING")
                        .set(OtaTask::getProgress, progress)
                        .set(OtaTask::getError, null)
                        .set(OtaTask::getUpdatedAt, now));
                if (updated == 0) {
                    return;
                }
                task.setStatus("DOWNLOADING");
                task.setProgress(progress);
                task.setError(null);
                task.setUpdatedAt(now);
            }
            log.info("OTA 进度 deviceId={} taskId={} status={} progress={} attempt={}/{}",
                    deviceId, taskId, task.getStatus(), task.getProgress(),
                    task.getAttempt(), task.getMaxAttempts());
        } catch (Exception e) {
            log.warn("OTA 进度处理失败 deviceId={} err={}", deviceId, e.getMessage());
        }
    }

    private void handleFailure(OtaTask task, String error, int progress) {
        int attempt = task.getAttempt() == null ? 1 : task.getAttempt();
        OffsetDateTime now = OffsetDateTime.now();
        if (OtaRetryPolicy.canRetry(task)) {
            int nextAttempt = attempt + 1;
            OffsetDateTime nextAt = now.plusSeconds(
                    OtaRetryPolicy.backoffSeconds(nextAttempt, retryBackoffSeconds));
            int updated = taskMapper.update(null, Wrappers.<OtaTask>lambdaUpdate()
                    .eq(OtaTask::getId, task.getId())
                    .eq(OtaTask::getStatus, task.getStatus())
                    .eq(OtaTask::getAttempt, attempt)
                    .set(OtaTask::getStatus, "PENDING")
                    .set(OtaTask::getAttempt, nextAttempt)
                    .set(OtaTask::getProgress, progress)
                    .set(OtaTask::getError, error)
                    .set(OtaTask::getNextAttemptAt, nextAt)
                    .set(OtaTask::getUpdatedAt, now)
                    .set(OtaTask::getFinishedAt, null));
            if (updated > 0) {
                task.setStatus("PENDING");
                task.setAttempt(nextAttempt);
                task.setProgress(progress);
                task.setError(error);
                task.setNextAttemptAt(nextAt);
                task.setUpdatedAt(now);
                task.setFinishedAt(null);
                meterRegistry.counter("coherer.ota.retry_scheduled").increment();
                log.info("OTA 设备失败，{} 秒后重试 deviceId={} taskId={} attempt={}/{}",
                        OtaRetryPolicy.backoffSeconds(nextAttempt, retryBackoffSeconds),
                        task.getDeviceId(), task.getId(), nextAttempt, task.getMaxAttempts());
            }
            return;
        }

        int updated = taskMapper.update(null, Wrappers.<OtaTask>lambdaUpdate()
                .eq(OtaTask::getId, task.getId())
                .eq(OtaTask::getStatus, task.getStatus())
                .eq(OtaTask::getAttempt, attempt)
                .set(OtaTask::getStatus, "FAILED")
                .set(OtaTask::getProgress, progress)
                .set(OtaTask::getError, error)
                .set(OtaTask::getNextAttemptAt, null)
                .set(OtaTask::getUpdatedAt, now)
                .set(OtaTask::getFinishedAt, now));
        if (updated == 0) {
            return;
        }
        task.setStatus("FAILED");
        task.setProgress(progress);
        task.setError(error);
        task.setNextAttemptAt(null);
        task.setUpdatedAt(now);
        task.setFinishedAt(now);
        meterRegistry.counter("coherer.ota.finished", "result", "failed").increment();
        publishFinished(task);
    }

    private void publishFinished(OtaTask task) {
        if (task.getRolloutId() != null) {
            eventPublisher.publishEvent(new OtaTaskFinishedEvent(task.getId(),
                    task.getRolloutId(), task.getDeviceId(), task.getStatus(), task.getError()));
        }
    }

    private boolean isTerminal(String status) {
        return "SUCCESS".equals(status) || "FAILED".equals(status) || "TIMEOUT".equals(status);
    }

    private String truncate(String value) {
        return value.length() <= 255 ? value : value.substring(0, 255);
    }
}
