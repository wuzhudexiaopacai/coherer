package com.github.wuzhudexiaopacai.audit;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.github.wuzhudexiaopacai.audit.entity.AuditLog;
import com.github.wuzhudexiaopacai.audit.mapper.AuditLogMapper;
import com.github.wuzhudexiaopacai.audit.vo.AuditLogVO;
import com.github.wuzhudexiaopacai.common.PageResult;
import com.github.wuzhudexiaopacai.common.RequestIps;
import io.micrometer.tracing.Tracer;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** 关键操作审计。记录失败不影响主流程。 */
@Service
public class AuditLogService extends ServiceImpl<AuditLogMapper, AuditLog> {

    private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);

    private final Tracer tracer;

    public AuditLogService(@Autowired(required = false) Tracer tracer) {
        this.tracer = tracer;
    }

    public void record(String action, String resource, Map<String, Object> detail) {
        recordAs(currentUsername(), currentIp(), action, resource, detail);
    }

    /** 记录失败结果（如登录失败、越权尝试），便于安全审计。 */
    public void recordFailure(String action, String resource, Map<String, Object> detail) {
        recordInternal(currentUsername(), currentIp(), action, resource, detail, "FAILURE");
    }

    public void recordAs(String username, String ip, String action, String resource,
                         Map<String, Object> detail) {
        recordInternal(username, ip, action, resource, detail, "SUCCESS");
    }

    public void recordAsFailure(String username, String ip, String action, String resource,
                                Map<String, Object> detail) {
        recordInternal(username, ip, action, resource, detail, "FAILURE");
    }

    private void recordInternal(String username, String ip, String action, String resource,
                                Map<String, Object> detail, String result) {
        try {
            AuditLog auditLog = new AuditLog();
            auditLog.setTenantId("default");
            auditLog.setUsername(StringUtils.hasText(username) ? username : "system");
            auditLog.setAction(action);
            auditLog.setResource(resource);
            auditLog.setDetail(detail == null ? Map.of() : detail);
            auditLog.setIp(ip);
            auditLog.setTraceId(currentTraceId());
            auditLog.setUserAgent(currentUserAgent());
            auditLog.setResult(result);
            save(auditLog);
        } catch (Exception e) {
            log.warn("审计日志写入失败 action={} resource={} err={}", action, resource, e.getMessage());
        }
    }

    public PageResult<AuditLogVO> page(long current, long size, String username, String action,
                                       String result, String traceId) {
        Page<AuditLog> page = page(new Page<>(current, size),
                Wrappers.<AuditLog>lambdaQuery()
                        .eq(StringUtils.hasText(username), AuditLog::getUsername, username)
                        .eq(StringUtils.hasText(action), AuditLog::getAction, action)
                        .eq(StringUtils.hasText(result), AuditLog::getResult, result)
                        .eq(StringUtils.hasText(traceId), AuditLog::getTraceId, traceId)
                        .orderByDesc(AuditLog::getCreatedAt));
        return PageResult.of(page.convert(this::toVO));
    }

    private String currentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return "anonymous";
        }
        return authentication.getName();
    }

    /**
     * 真实客户端 IP：优先取代理链首个地址（X-Forwarded-For），否则 remoteAddr。
     * 部署在反向代理后面时必须这样取，否则所有请求 IP 都是网关地址。
     */
    private String currentIp() {
        ServletRequestAttributes attributes = servletAttributes();
        return attributes == null ? null : RequestIps.resolve(attributes.getRequest());
    }

    private String currentUserAgent() {
        ServletRequestAttributes attributes = servletAttributes();
        if (attributes == null) {
            return null;
        }
        return truncate(attributes.getRequest().getHeader("User-Agent"), 256);
    }

    private String currentTraceId() {
        if (tracer == null) {
            return null;
        }
        try {
            var span = tracer.currentSpan();
            return span == null ? null : span.context().traceId();
        } catch (Exception e) {
            return null;
        }
    }

    private ServletRequestAttributes servletAttributes() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        return attributes instanceof ServletRequestAttributes servlet ? servlet : null;
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private AuditLogVO toVO(AuditLog auditLog) {
        AuditLogVO vo = new AuditLogVO();
        vo.setId(auditLog.getId());
        vo.setUsername(auditLog.getUsername());
        vo.setAction(auditLog.getAction());
        vo.setResource(auditLog.getResource());
        vo.setDetail(auditLog.getDetail());
        vo.setIp(auditLog.getIp());
        vo.setTraceId(auditLog.getTraceId());
        vo.setResult(auditLog.getResult());
        vo.setUserAgent(auditLog.getUserAgent());
        vo.setCreatedAt(auditLog.getCreatedAt());
        return vo;
    }
}
