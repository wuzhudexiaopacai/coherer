-- Coherer A2：平台用户与角色（RBAC 基础）

CREATE TABLE sys_user (
    id            BIGINT       PRIMARY KEY,
    tenant_id     VARCHAR(32)  NOT NULL DEFAULT 'default',
    username      VARCHAR(64)  NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    nickname      VARCHAR(64)  NOT NULL,
    role          VARCHAR(16)  NOT NULL DEFAULT 'VIEWER', -- ADMIN / OPERATOR / VIEWER
    status        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted       SMALLINT     NOT NULL DEFAULT 0
);

COMMENT ON TABLE sys_user IS '平台用户（RBAC 基础，单角色）';
COMMENT ON COLUMN sys_user.role IS 'ADMIN 管理员 / OPERATOR 运维 / VIEWER 只读';

CREATE UNIQUE INDEX uk_sys_user_username ON sys_user (username) WHERE deleted = 0;
