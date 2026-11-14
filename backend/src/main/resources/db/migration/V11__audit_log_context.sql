-- Coherer B+：审计日志补全（traceId、执行结果、User-Agent、代理真实 IP）

ALTER TABLE audit_log ADD COLUMN trace_id   VARCHAR(64);
ALTER TABLE audit_log ADD COLUMN result     VARCHAR(16) NOT NULL DEFAULT 'SUCCESS';
ALTER TABLE audit_log ADD COLUMN user_agent VARCHAR(256);

COMMENT ON COLUMN audit_log.trace_id IS '链路追踪 ID，可与日志/ Zipkin 关联';
COMMENT ON COLUMN audit_log.result IS 'SUCCESS / FAILURE';
COMMENT ON COLUMN audit_log.user_agent IS '调用方 User-Agent，便于排查异常客户端';

CREATE INDEX idx_audit_trace ON audit_log (trace_id);
CREATE INDEX idx_audit_result ON audit_log (result);
