package com.github.wuzhudexiaopacai.integration;

import com.github.wuzhudexiaopacai.command.service.CommandPublisher;
import com.github.wuzhudexiaopacai.ota.service.OtaPublisher;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** 集成测试用 Publisher：记录平台下发的命令，不依赖真实 EMQX。 */
public class RecordingPublisher implements CommandPublisher, OtaPublisher {

    public final List<OtaCommand> otaCommands = new CopyOnWriteArrayList<>();
    public final List<Command> commands = new CopyOnWriteArrayList<>();
    public volatile boolean available = true;

    @Override
    public boolean publishOta(String tenantId, String deviceId, String hostKey,
                              Long taskId, Long firmwareId, String version,
                              String downloadUrl, String sha256, long sizeBytes, int attempt) {
        if (!available) {
            return false;
        }
        otaCommands.add(new OtaCommand(tenantId, deviceId, hostKey, taskId, firmwareId,
                version, downloadUrl, sha256, sizeBytes, attempt));
        return true;
    }

    @Override
    public boolean publishCommand(String tenantId, String deviceId, String hostKey,
                                  String identifier, String requestId,
                                  Map<String, Object> params) {
        if (!available) {
            return false;
        }
        commands.add(new Command(tenantId, deviceId, hostKey, identifier, requestId, params));
        return true;
    }

    public void clear() {
        otaCommands.clear();
        commands.clear();
        available = true;
    }

    public record OtaCommand(String tenantId, String deviceId, String hostKey, Long taskId,
                             Long firmwareId, String version, String downloadUrl,
                             String sha256, long sizeBytes, int attempt) {
    }

    public record Command(String tenantId, String deviceId, String hostKey, String identifier,
                          String requestId, Map<String, Object> params) {
    }
}
