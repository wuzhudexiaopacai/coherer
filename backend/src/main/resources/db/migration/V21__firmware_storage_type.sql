-- Coherer：固件存储后端标记（切换 S3 后仍能读取历史本地文件）

ALTER TABLE firmware ADD COLUMN storage_type VARCHAR(16) NOT NULL DEFAULT 'LOCAL';
COMMENT ON COLUMN firmware.storage_type IS 'LOCAL / S3，决定读取/删除时路由到哪个存储后端';

CREATE INDEX idx_firmware_storage_type ON firmware (storage_type);
