-- Coherer：SaaS 多租户基础
-- 租户是最外层隔离边界；项目/空间/设备/主机/告警/OTA/审计均带 tenant_id。

CREATE TABLE tenant (
    id                  VARCHAR(32)  PRIMARY KEY,
    name                VARCHAR(64)  NOT NULL,
    status              VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    plan                VARCHAR(32)  NOT NULL DEFAULT 'STANDARD',
    description         VARCHAR(255),
    default_project_id  BIGINT,
    default_space_id    BIGINT,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted             SMALLINT     NOT NULL DEFAULT 0
);

COMMENT ON TABLE tenant IS '租户：SaaS 最外层隔离边界';
INSERT INTO tenant (id, name, status, plan, default_project_id, default_space_id)
VALUES ('default', '默认租户', 'ACTIVE', 'STANDARD', 1, 1)
ON CONFLICT (id) DO NOTHING;

-- 平台管理员标记：只有平台管理员可以管理租户
ALTER TABLE sys_user ADD COLUMN platform_admin SMALLINT NOT NULL DEFAULT 0;
UPDATE sys_user SET platform_admin = 1
WHERE tenant_id = 'default' AND username = 'admin' AND deleted = 0;

-- 用户名从全局唯一改为租户内唯一
DROP INDEX IF EXISTS uk_sys_user_username;
CREATE UNIQUE INDEX uk_sys_user_tenant_username
    ON sys_user (tenant_id, username) WHERE deleted = 0;

-- 产品标识改为租户内唯一（产品定义属于租户）
DROP INDEX IF EXISTS uk_product_key;
CREATE UNIQUE INDEX uk_product_key
    ON product (tenant_id, product_key) WHERE deleted = 0;

-- 补齐遗漏 tenant_id 的表
ALTER TABLE thing_model ADD COLUMN tenant_id VARCHAR(32);
UPDATE thing_model tm
SET tenant_id = p.tenant_id
FROM product p
WHERE tm.product_id = p.id;
UPDATE thing_model SET tenant_id = 'default' WHERE tenant_id IS NULL;
ALTER TABLE thing_model ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE thing_model ALTER COLUMN tenant_id SET DEFAULT 'default';
CREATE INDEX idx_thing_model_tenant_product ON thing_model (tenant_id, product_id);

ALTER TABLE ota_rollout_device ADD COLUMN tenant_id VARCHAR(32);
UPDATE ota_rollout_device rd
SET tenant_id = r.tenant_id
FROM ota_rollout r
WHERE rd.rollout_id = r.id;
UPDATE ota_rollout_device SET tenant_id = 'default' WHERE tenant_id IS NULL;
ALTER TABLE ota_rollout_device ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE ota_rollout_device ALTER COLUMN tenant_id SET DEFAULT 'default';
CREATE INDEX idx_ota_rollout_device_tenant ON ota_rollout_device (tenant_id, rollout_id);
