-- Coherer M1：遥测数据简表（M2 迁移至 TDengine，此处用于打通垂直链路）

CREATE TABLE telemetry (
    id          BIGINT       PRIMARY KEY,
    tenant_id   VARCHAR(32)  NOT NULL,
    product_id  BIGINT,
    device_id   VARCHAR(64)  NOT NULL,
    ts          TIMESTAMPTZ  NOT NULL,
    params      JSONB        NOT NULL,
    received_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON TABLE telemetry IS '遥测数据（M1 简表，M2 切换 TDengine）';
COMMENT ON COLUMN telemetry.ts IS '设备上报时间';
COMMENT ON COLUMN telemetry.received_at IS '服务端入库时间';

CREATE INDEX idx_telemetry_device_ts ON telemetry (device_id, ts DESC);
CREATE INDEX idx_telemetry_ts ON telemetry (ts);
