-- Coherer M1：产品与设备基础表
-- tenant_id 预埋，多租户在 M5 启用（当前默认 'default'）

CREATE TABLE product (
    id           BIGINT       PRIMARY KEY,
    tenant_id    VARCHAR(32)  NOT NULL DEFAULT 'default',
    product_key  VARCHAR(64)  NOT NULL,
    name         VARCHAR(64)  NOT NULL,
    category     VARCHAR(32)  NOT NULL DEFAULT 'GENERIC',
    node_type    VARCHAR(16)  NOT NULL DEFAULT 'DIRECT',
    auth_type    VARCHAR(16)  NOT NULL DEFAULT 'SECRET',
    status       VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    description  VARCHAR(255),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted      SMALLINT     NOT NULL DEFAULT 0
);

COMMENT ON TABLE product IS '产品（同一类设备的模板）';
COMMENT ON COLUMN product.product_key IS '产品标识，MQTT 主题与物模型使用';
COMMENT ON COLUMN product.node_type IS '节点类型：DIRECT 直连 / GATEWAY 网关';
COMMENT ON COLUMN product.auth_type IS '鉴权方式：SECRET 一机一密';
COMMENT ON COLUMN product.deleted IS '逻辑删除：0 正常，1 删除';

-- 逻辑删除场景用部分唯一索引保证未删除记录唯一
CREATE UNIQUE INDEX uk_product_key ON product (product_key) WHERE deleted = 0;
CREATE INDEX idx_product_tenant ON product (tenant_id);

CREATE TABLE device (
    id             BIGINT       PRIMARY KEY,
    tenant_id      VARCHAR(32)  NOT NULL DEFAULT 'default',
    product_id     BIGINT       NOT NULL,
    device_id      VARCHAR(64)  NOT NULL,
    name           VARCHAR(64)  NOT NULL,
    secret_cipher  VARCHAR(255) NOT NULL,
    status         VARCHAR(16)  NOT NULL DEFAULT 'INACTIVE',
    tags           JSONB        NOT NULL DEFAULT '{}'::jsonb,
    last_online_at TIMESTAMPTZ,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted        SMALLINT     NOT NULL DEFAULT 0
);

COMMENT ON TABLE device IS '设备';
COMMENT ON COLUMN device.device_id IS '设备唯一标识（MQTT clientId）';
COMMENT ON COLUMN device.secret_cipher IS '设备密钥密文（AES-256-GCM）';
COMMENT ON COLUMN device.status IS 'INACTIVE / ONLINE / OFFLINE / DISABLED';
COMMENT ON COLUMN device.deleted IS '逻辑删除：0 正常，1 删除';

CREATE UNIQUE INDEX uk_device_id ON device (device_id) WHERE deleted = 0;
CREATE INDEX idx_device_product ON device (product_id);
CREATE INDEX idx_device_tenant_status ON device (tenant_id, status);
