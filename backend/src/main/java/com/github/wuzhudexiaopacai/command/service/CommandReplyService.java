package com.github.wuzhudexiaopacai.command.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wuzhudexiaopacai.command.entity.DeviceCommand;
import com.github.wuzhudexiaopacai.command.mapper.DeviceCommandMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.OffsetDateTime;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 处理设备命令回执：按 requestId 更新命令最终状态。 */
@Service
@RequiredArgsConstructor
public class CommandReplyService {

    private static final Logger log = LoggerFactory.getLogger(CommandReplyService.class);

    private final DeviceCommandMapper commandMapper;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public void onReply(String tenantId, String deviceId, byte[] payload) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            String requestId = root.path("id").asText("");
            if (requestId.isBlank()) {
                log.debug("命令回执缺少 id，忽略 deviceId={}", deviceId);
                return;
            }
            DeviceCommand command = commandMapper.selectOne(
                    Wrappers.<DeviceCommand>lambdaQuery()
                            .eq(DeviceCommand::getRequestId, requestId)
                            .eq(DeviceCommand::getDeviceId, deviceId)
                            .last("limit 1"));
            if (command == null) {
                log.warn("收到未知命令回执 deviceId={} requestId={}", deviceId, requestId);
                return;
            }
            if (!"PENDING".equals(command.getStatus()) && !"SENT".equals(command.getStatus())) {
                log.debug("命令回执重复，忽略 requestId={} status={}", requestId, command.getStatus());
                return;
            }

            boolean success = root.has("success")
                    ? root.path("success").asBoolean(false)
                    : root.path("code").asInt(-1) == 0;
            command.setStatus(success ? "SUCCESS" : "FAILED");
            JsonNode resultNode = root.path("result");
            if (resultNode.isObject()) {
                command.setResult(objectMapper.convertValue(
                        resultNode, new TypeReference<Map<String, Object>>() {
                        }));
            }
            if (!success) {
                String error = root.path("error").asText(
                        root.path("message").asText("设备返回失败"));
                command.setError(error.length() > 255 ? error.substring(0, 255) : error);
            }
            command.setRepliedAt(OffsetDateTime.now());
            commandMapper.updateById(command);
            meterRegistry.counter("coherer.command.replied",
                    "result", success ? "success" : "failed").increment();
            log.info("命令回执 requestId={} deviceId={} status={}",
                    requestId, deviceId, command.getStatus());
        } catch (Exception e) {
            log.warn("命令回执处理失败 deviceId={} err={}", deviceId, e.getMessage());
        }
    }
}
