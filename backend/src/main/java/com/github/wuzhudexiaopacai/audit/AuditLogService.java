package com.github.wuzhudexiaopacai.audit;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.github.wuzhudexiaopacai.audit.entity.AuditLog;
import com.github.wuzhudexiaopacai.audit.mapper.AuditLogMapper;
import com.github.wuzhudexiaopacai.audit.vo.AuditLogVO;
import com.github.wuzhudexiaopacai.common.PageResult;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public void record(String action, String resource, Map<String, Object> detail) {
        recordAs(currentUsername(), currentIp(), action, resource, detail);
    }

    public void recordAs(String username, String ip, String action, String resource,
                         Map<String, Object> detail) {
        try {
            AuditLog auditLog = new AuditLog();
            auditLog.setTenantId("default");
            auditLog.setUsername(StringUtils.hasText(username) ? username : "system");
            auditLog.setAction(action);
            auditLog.setResource(resource);
            auditLog.setDetail(detail == null ? Map.of() : detail);
            auditLog.setIp(ip);
            save(auditLog);
        } catch (Exception e) {
            log.warn("审计日志写入失败 action={} resource={} err={}", action, resource, e.getMessage());
        }
    }

    public PageResult<AuditLogVO> page(long current, long size, String username, String action) {
        Page<AuditLog> page = page(new Page<>(current, size),
                Wrappers.<AuditLog>lambdaQuery()
                        .eq(StringUtils.hasText(username), AuditLog::getUsername, username)
                        .eq(StringUtils.hasText(action), AuditLog::getAction, action)
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

    private String currentIp() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            return servletAttributes.getRequest().getRemoteAddr();
        }
        return null;
    }

    private AuditLogVO toVO(AuditLog auditLog) {
        AuditLogVO vo = new AuditLogVO();
        vo.setId(auditLog.getId());
        vo.setUsername(auditLog.getUsername());
        vo.setAction(auditLog.getAction());
        vo.setResource(auditLog.getResource());
        vo.setDetail(auditLog.getDetail());
        vo.setIp(auditLog.getIp());
        vo.setCreatedAt(auditLog.getCreatedAt());
        return vo;
    }
}
