-- Coherer：固件与 OTA 升级任务

CREATE TABLE firmware (
    id            BIGINT       PRIMARY KEY,
    tenant_id     VARCHAR(32)  NOT NULL DEFAULT 'default',
    product_id    BIGINT       NOT NULL,
    version       VARCHAR(32)  NOT NULL,
    file_name     VARCHAR(128) NOT NULL,
    content_type  VARCHAR(64),
    size_bytes    BIGINT       NOT NULL,
    sha256        CHAR(64)     NOT NULL,
    storage_path  VARCHAR(255) NOT NULL,
    created_by    VARCHAR(64),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted       SMALLINT     NOT NULL DEFAULT 0
);

COMMENT ON TABLE firmware IS '产品固件包：版本、SHA256、本地/对象存储路径';
CREATE UNIQUE INDEX uk_firmware_product_version ON firmware (product_id, version)
    WHERE deleted = 0;
CREATE INDEX idx_firmware_product ON firmware (product_id);

CREATE TABLE ota_task (
    id           BIGINT       PRIMARY KEY,
    tenant_id    VARCHAR(32)  NOT NULL DEFAULT 'default',
    firmware_id  BIGINT       NOT NULL,
    device_id    VARCHAR(64)  NOT NULL,
    status       VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    progress     INT          NOT NULL DEFAULT 0,
    error        VARCHAR(255),
    created_by   VARCHAR(64),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    sent_at      TIMESTAMPTZ,
    finished_at  TIMESTAMPTZ
);

COMMENT ON TABLE ota_task IS 'OTA 升级任务：PENDING/SENT/DOWNLOADING/SUCCESS/FAILED/TIMEOUT';
CREATE INDEX idx_ota_task_device_created ON ota_task (device_id, created_at DESC);
CREATE INDEX idx_ota_task_status_updated ON ota_task (status, updated_at);
-- 硬约束：同一设备同时只允许一个进行中的升级任务，避免并发启动绕过应用层检查
CREATE UNIQUE INDEX uk_ota_task_active_device ON ota_task (device_id)
    WHERE status IN ('PENDING', 'SENT', 'DOWNLOADING');
