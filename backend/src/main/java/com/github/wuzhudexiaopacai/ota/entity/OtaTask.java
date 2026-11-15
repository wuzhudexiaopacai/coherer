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

    /** 当前已尝试次数，首次为 1 */
    private Integer attempt;

    /** 最大尝试次数，1 表示不重试 */
    private Integer maxAttempts;

    /** 下一次重试时间 */
    private OffsetDateTime nextAttemptAt;

    /** 创建任务时设备当前固件版本 */
    private String fromVersion;

    /** NORMAL / ROLLBACK */
    private String taskType;

    /** 回退任务对应的原升级任务 */
    private Long rollbackOfTaskId;

    private Long rolloutId;

    private Integer batchNo;

    private String createdBy;

    private OffsetDateTime createdAt;

    /** 最后状态变更时间，用于超时巡检 */
    private OffsetDateTime updatedAt;

    private OffsetDateTime sentAt;

    private OffsetDateTime finishedAt;
}
