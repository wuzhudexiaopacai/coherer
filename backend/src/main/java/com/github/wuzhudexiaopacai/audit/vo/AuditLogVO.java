package com.github.wuzhudexiaopacai.audit.vo;

import java.time.OffsetDateTime;
import java.util.Map;
import lombok.Data;

@Data
public class AuditLogVO {

    private Long id;

    private String username;

    private String action;

    private String resource;

    private Map<String, Object> detail;

    private String ip;

    private String traceId;

    private String result;

    private String userAgent;

    private OffsetDateTime createdAt;
}
