-- Coherer：JWT 会话版本，用于禁用/降级/改密/登出后立即吊销旧令牌

ALTER TABLE sys_user ADD COLUMN token_version INT NOT NULL DEFAULT 0;

COMMENT ON COLUMN sys_user.token_version IS '令牌版本；变更后旧 access/refresh token 全部失效';
