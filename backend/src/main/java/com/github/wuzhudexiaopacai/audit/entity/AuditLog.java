package com.github.wuzhudexiaopacai.audit.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import java.time.OffsetDateTime;
import java.util.Map;
import lombok.Data;

@Data
@TableName(value = "audit_log", autoResultMap = true)
public class AuditLog {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String tenantId;

    private String username;

    private String action;

    private String resource;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> detail;

    private String ip;

    /** 链路追踪 ID：与日志/ Zipkin 关联 */
    private String traceId;

    /** SUCCESS / FAILURE */
    private String result;

    private String userAgent;

    private OffsetDateTime createdAt;
}
