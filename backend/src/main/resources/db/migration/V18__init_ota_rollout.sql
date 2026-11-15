-- Coherer：OTA 灰度批次（按设备/空间/全量分批下发，支持暂停与失败率熔断）

CREATE TABLE ota_rollout (
    id                        BIGINT       PRIMARY KEY,
    tenant_id                 VARCHAR(32)  NOT NULL DEFAULT 'default',
    firmware_id               BIGINT       NOT NULL,
    product_id                BIGINT       NOT NULL,
    name                      VARCHAR(64)  NOT NULL,
    target_type               VARCHAR(16)  NOT NULL,
    target_space_id           BIGINT,
    status                    VARCHAR(24)  NOT NULL DEFAULT 'PENDING',
    batch_size                INT          NOT NULL,
    failure_threshold_percent INT          NOT NULL DEFAULT 20,
    auto_advance              BOOLEAN      NOT NULL DEFAULT TRUE,
    pause_reason              VARCHAR(255),
    current_batch             INT          NOT NULL DEFAULT 0,
    reviewed_batch            INT          NOT NULL DEFAULT 0,
    total_count               INT          NOT NULL DEFAULT 0,
    pending_count             INT          NOT NULL DEFAULT 0,
    running_count             INT          NOT NULL DEFAULT 0,
    success_count             INT          NOT NULL DEFAULT 0,
    failed_count              INT          NOT NULL DEFAULT 0,
    created_by                VARCHAR(64),
    created_at                TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ  NOT NULL DEFAULT now(),
    started_at                TIMESTAMPTZ,
    finished_at               TIMESTAMPTZ
);

COMMENT ON TABLE ota_rollout IS 'OTA 灰度批次：PENDING/RUNNING/PAUSED/SUCCESS/PARTIAL_FAILED/FAILED/CANCELED';
CREATE INDEX idx_ota_rollout_status ON ota_rollout (status, updated_at);
CREATE INDEX idx_ota_rollout_product ON ota_rollout (product_id, created_at DESC);

CREATE TABLE ota_rollout_device (
    id          BIGINT       PRIMARY KEY,
    rollout_id  BIGINT       NOT NULL,
    device_id   VARCHAR(64)  NOT NULL,
    batch_no    INT          NOT NULL,
    status      VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    task_id     BIGINT,
    error       VARCHAR(255),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (rollout_id, device_id)
);

COMMENT ON TABLE ota_rollout_device IS '灰度批次成员快照：PENDING/RUNNING/SUCCESS/FAILED/SKIPPED';
CREATE INDEX idx_ota_rollout_device_batch ON ota_rollout_device (rollout_id, batch_no, status);

ALTER TABLE ota_task ADD COLUMN rollout_id BIGINT;
ALTER TABLE ota_task ADD COLUMN batch_no INT;
CREATE INDEX idx_ota_task_rollout ON ota_task (rollout_id, device_id);
