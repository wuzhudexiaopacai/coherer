package com.github.wuzhudexiaopacai.ota.event;

/** OTA 任务进入终态（SUCCESS/FAILED/TIMEOUT）时通知灰度批次。 */
public record OtaTaskFinishedEvent(Long taskId, Long rolloutId, String deviceId,
                                   String status, String error) {

    public boolean terminal() {
        return "SUCCESS".equals(status) || "FAILED".equals(status) || "TIMEOUT".equals(status);
    }
}
