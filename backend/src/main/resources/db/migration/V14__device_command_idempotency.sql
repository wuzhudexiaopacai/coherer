-- Coherer：命令幂等键（客户端可选传入，重复提交返回首次结果）

ALTER TABLE device_command ADD COLUMN client_request_id VARCHAR(64);

CREATE UNIQUE INDEX uk_command_client_request
    ON device_command (device_id, client_request_id)
    WHERE client_request_id IS NOT NULL;

COMMENT ON COLUMN device_command.client_request_id IS '客户端幂等键；同一设备重复提交返回首次命令';
