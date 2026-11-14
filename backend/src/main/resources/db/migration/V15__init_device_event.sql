-- Coherer：设备事件上报（物模型 EVENT）

CREATE TABLE device_event (
    id          BIGINT       PRIMARY KEY,
    tenant_id   VARCHAR(32)  NOT NULL DEFAULT 'default',
    device_id   VARCHAR(64)  NOT NULL,
    product_id  BIGINT,
    identifier  VARCHAR(64)  NOT NULL,
    event_id    VARCHAR(64),
    ts          BIGINT       NOT NULL,
    params      JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON TABLE device_event IS '设备事件：按物模型 EVENT 定义校验后落库';
COMMENT ON COLUMN device_event.event_id IS '设备侧事件 ID，用于幂等去重';

CREATE UNIQUE INDEX uk_device_event ON device_event (device_id, event_id)
    WHERE event_id IS NOT NULL;
CREATE INDEX idx_device_event_device_ts ON device_event (device_id, ts DESC);
CREATE INDEX idx_device_event_created ON device_event (created_at DESC);
