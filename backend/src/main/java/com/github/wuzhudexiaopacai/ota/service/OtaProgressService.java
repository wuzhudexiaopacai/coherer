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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/** 处理设备 OTA 进度上报。 */
@Service
@RequiredArgsConstructor
public class OtaProgressService {

    private static final Logger log = LoggerFactory.getLogger(OtaProgressService.class);

    private final OtaTaskMapper taskMapper;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final MeterRegistry meterRegistry;

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
            if ("SUCCESS".equals(task.getStatus()) || "FAILED".equals(task.getStatus())
                    || "TIMEOUT".equals(task.getStatus())) {
                return;
            }
            String status = root.path("status").asText("").toUpperCase();
            int progress = Math.min(Math.max(root.path("progress").asInt(task.getProgress() == null
                    ? 0 : task.getProgress()), 0), 100);
            task.setProgress(progress);
            task.setUpdatedAt(OffsetDateTime.now());
            if ("SUCCESS".equals(status) || progress >= 100) {
                task.setStatus("SUCCESS");
                task.setProgress(100);
                task.setFinishedAt(OffsetDateTime.now());
            } else if ("DOWNLOADING".equals(status)) {
                task.setStatus("DOWNLOADING");
            } else if ("FAILED".equals(status)) {
                task.setStatus("FAILED");
                task.setFinishedAt(OffsetDateTime.now());
                String error = root.path("error").asText("设备升级失败");
                task.setError(error.length() > 255 ? error.substring(0, 255) : error);
            }
            taskMapper.updateById(task);
            if ("SUCCESS".equals(task.getStatus()) || "FAILED".equals(task.getStatus())) {
                meterRegistry.counter("coherer.ota.finished",
                        "result", task.getStatus().toLowerCase()).increment();
                if (task.getRolloutId() != null) {
                    eventPublisher.publishEvent(new OtaTaskFinishedEvent(task.getId(),
                            task.getRolloutId(), task.getDeviceId(), task.getStatus(),
                            task.getError()));
                }
            }
            log.info("OTA 进度 deviceId={} taskId={} status={} progress={}",
                    deviceId, taskId, task.getStatus(), task.getProgress());
        } catch (Exception e) {
            log.warn("OTA 进度处理失败 deviceId={} err={}", deviceId, e.getMessage());
        }
    }
}
