package com.github.wuzhudexiaopacai.ota.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Data;

@Data
@TableName("ota_task")
public class OtaTask {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String tenantId;

    private Long firmwareId;

    private String deviceId;

    /** PENDING / SENT / DOWNLOADING / SUCCESS / FAILED / TIMEOUT */
    private String status;

    private Integer progress;

    private String error;

    private String createdBy;

    private OffsetDateTime createdAt;

    /** 最后状态变更时间，用于超时巡检 */
    private OffsetDateTime updatedAt;

    private OffsetDateTime sentAt;

    private OffsetDateTime finishedAt;
}
