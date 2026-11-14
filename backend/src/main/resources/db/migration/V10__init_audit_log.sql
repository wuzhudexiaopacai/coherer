-- Coherer B：审计日志

CREATE TABLE audit_log (
    id         BIGINT       PRIMARY KEY,
    tenant_id  VARCHAR(32)  NOT NULL DEFAULT 'default',
    username   VARCHAR(64)  NOT NULL,
    action     VARCHAR(64)  NOT NULL,
    resource   VARCHAR(128),
    detail     JSONB        NOT NULL DEFAULT '{}'::jsonb,
    ip         VARCHAR(64),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON TABLE audit_log IS '关键操作审计：登录、设备、告警、空间、影子等';
CREATE INDEX idx_audit_created ON audit_log (created_at DESC);
CREATE INDEX idx_audit_username ON audit_log (username);
