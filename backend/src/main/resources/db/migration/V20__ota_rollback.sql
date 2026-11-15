-- Coherer：OTA 版本回退（记录升级前后版本，支持按设备回退）

ALTER TABLE device ADD COLUMN firmware_version VARCHAR(32);
COMMENT ON COLUMN device.firmware_version IS '设备最近一次上报/升级成功的固件版本';

ALTER TABLE ota_task ADD COLUMN from_version VARCHAR(32);
ALTER TABLE ota_task ADD COLUMN task_type VARCHAR(16) NOT NULL DEFAULT 'NORMAL';
ALTER TABLE ota_task ADD COLUMN rollback_of_task_id BIGINT;

COMMENT ON COLUMN ota_task.from_version IS '创建任务时设备当前固件版本';
COMMENT ON COLUMN ota_task.task_type IS 'NORMAL / ROLLBACK';
COMMENT ON COLUMN ota_task.rollback_of_task_id IS '回退任务对应的原升级任务';

CREATE INDEX idx_ota_task_type ON ota_task (task_type, created_at DESC);
