-- Coherer M3：设备命令下发与回执

CREATE TABLE device_command (
    id             BIGINT       PRIMARY KEY,
    tenant_id      VARCHAR(32)  NOT NULL DEFAULT 'default',
    device_id      VARCHAR(64)  NOT NULL,
    product_id     BIGINT,
    identifier     VARCHAR(64)  NOT NULL,
    request_id     VARCHAR(64)  NOT NULL,
    params         JSONB        NOT NULL DEFAULT '{}'::jsonb,
    status         VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    result         JSONB,
    error          VARCHAR(255),
    operator       VARCHAR(64),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    sent_at        TIMESTAMPTZ,
    replied_at     TIMESTAMPTZ
);

COMMENT ON TABLE device_command IS '设备服务调用：下发命令与设备回执';
COMMENT ON COLUMN device_command.status IS 'PENDING / SENT / SUCCESS / FAILED / TIMEOUT';
COMMENT ON COLUMN device_command.request_id IS '命令关联 ID，设备回执时原样带回';

CREATE UNIQUE INDEX uk_command_request ON device_command (request_id);
CREATE INDEX idx_command_device_created ON device_command (device_id, created_at DESC);
CREATE INDEX idx_command_status_sent ON device_command (status, sent_at);
