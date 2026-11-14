package com.github.wuzhudexiaopacai.auth.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Data;

@Data
@TableName("sys_user")
public class SysUser {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String tenantId;

    private String username;

    private String passwordHash;

    private String nickname;

    /** ADMIN / OPERATOR / VIEWER */
    private String role;

    /** 数据范围：空间 ID（含子空间）；NULL 表示全部空间 */
    private Long spaceId;

    /** ACTIVE / DISABLED */
    private String status;

    /** 令牌版本：禁用、降级、改密、登出时 +1，旧 JWT 立即失效 */
    private Integer tokenVersion;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
