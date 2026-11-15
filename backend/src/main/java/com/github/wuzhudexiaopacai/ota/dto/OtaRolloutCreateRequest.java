package com.github.wuzhudexiaopacai.ota.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Data;

@Data
public class OtaRolloutCreateRequest {

    @NotNull(message = "固件不能为空")
    private Long firmwareId;

    /** 所属项目；缺省为默认项目 */
    private Long projectId;

    @NotBlank(message = "批次名称不能为空")
    @Size(max = 64, message = "批次名称最长 64")
    private String name;

    /** DEVICE 指定设备 / SPACE 空间（含子空间）/ ALL 产品全量 */
    @NotBlank(message = "目标类型不能为空")
    @Pattern(regexp = "DEVICE|SPACE|ALL", message = "目标类型仅支持 DEVICE/SPACE/ALL")
    private String targetType;

    private Long spaceId;

    @Size(max = 1000, message = "单批最多指定 1000 台设备")
    private List<String> deviceIds;

    @NotNull(message = "批次大小不能为空")
    @Min(value = 1, message = "批次大小至少 1")
    @Max(value = 500, message = "批次大小最大 500")
    private Integer batchSize;

    /** 单批失败率超过该百分比时暂停后续批次 */
    @NotNull(message = "失败率阈值不能为空")
    @Min(value = 0, message = "失败率阈值最小 0")
    @Max(value = 100, message = "失败率阈值最大 100")
    private Integer failureThresholdPercent;

    /** 每台设备失败/超时后的最大尝试次数，默认 1 */
    @Min(value = 1, message = "最大尝试次数至少 1")
    @Max(value = 5, message = "最大尝试次数最多 5")
    private Integer maxAttempts;

    /** false 时每批完成后暂停，等待人工恢复 */
    private Boolean autoAdvance;
}
