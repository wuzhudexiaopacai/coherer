-- Coherer：边缘接入主机（网关）与设备绑定

CREATE TABLE iot_host (
    id               BIGINT       PRIMARY KEY,
    tenant_id        VARCHAR(32)  NOT NULL DEFAULT 'default',
    host_id          VARCHAR(64)  NOT NULL,
    name             VARCHAR(64)  NOT NULL,
    space_id         BIGINT       NOT NULL,
    model             VARCHAR(64),
    firmware_version VARCHAR(64),
    secret_cipher    VARCHAR(512) NOT NULL,
    status           VARCHAR(16)  NOT NULL DEFAULT 'INACTIVE',
    last_online_at   TIMESTAMPTZ,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted          SMALLINT     NOT NULL DEFAULT 0
);

COMMENT ON TABLE iot_host IS '边缘接入主机/网关：管理子设备，可独立接入或仅作命令转发';
COMMENT ON COLUMN iot_host.status IS 'INACTIVE / ONLINE / OFFLINE / DISABLED';

CREATE UNIQUE INDEX uk_iot_host_id ON iot_host (host_id) WHERE deleted = 0;
CREATE INDEX idx_iot_host_space ON iot_host (space_id);

ALTER TABLE device ADD COLUMN host_id BIGINT;
COMMENT ON COLUMN device.host_id IS '所属边缘主机；NULL 表示 Wi-Fi/以太网设备直连';
CREATE INDEX idx_device_host ON device (host_id);
