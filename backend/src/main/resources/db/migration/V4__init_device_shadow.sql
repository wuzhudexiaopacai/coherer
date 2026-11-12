-- Coherer M2：设备影子（desired / reported + 乐观锁版本号）

CREATE TABLE device_shadow (
    id         BIGINT       PRIMARY KEY,
    tenant_id  VARCHAR(32)  NOT NULL,
    device_id  VARCHAR(64)  NOT NULL,
    desired    JSONB        NOT NULL DEFAULT '{}'::jsonb,
    reported   JSONB        NOT NULL DEFAULT '{}'::jsonb,
    version    BIGINT       NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON TABLE device_shadow IS '设备影子：desired 期望状态 / reported 上报状态';
COMMENT ON COLUMN device_shadow.version IS '乐观锁版本号，每次变更 +1';

CREATE UNIQUE INDEX uk_device_shadow_device ON device_shadow (device_id);
