package com.github.wuzhudexiaopacai.ota.vo;

import lombok.Data;

@Data
public class OtaRolloutDeviceVO {

    private String deviceId;

    private String deviceName;

    private Integer batchNo;

    private String status;

    private Long taskId;

    private String taskStatus;

    private Integer progress;

    private String error;
}
