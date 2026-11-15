-- Coherer：OTA 失败自动重试（attempt / 退避 / 最大次数）

ALTER TABLE ota_task ADD COLUMN attempt INT NOT NULL DEFAULT 1;
ALTER TABLE ota_task ADD COLUMN max_attempts INT NOT NULL DEFAULT 1;
ALTER TABLE ota_task ADD COLUMN next_attempt_at TIMESTAMPTZ;

COMMENT ON COLUMN ota_task.attempt IS '当前已尝试次数，首次下发为 1';
COMMENT ON COLUMN ota_task.max_attempts IS '最大尝试次数，1 表示不重试';
COMMENT ON COLUMN ota_task.next_attempt_at IS '下一次重试时间；NULL 表示无需/正在重试';

CREATE INDEX idx_ota_task_retry ON ota_task (status, next_attempt_at);

ALTER TABLE ota_rollout ADD COLUMN max_attempts INT NOT NULL DEFAULT 1;
COMMENT ON COLUMN ota_rollout.max_attempts IS '批次内每台设备的最大尝试次数';
