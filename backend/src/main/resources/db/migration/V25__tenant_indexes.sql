-- Coherer：多租户索引对齐
-- 业务查询现在统一带 tenant_id 前缀；原单列索引（created_at/status 等）在多租户下
-- 选择性明显下降，补租户前缀复合索引，并删除被完全覆盖的旧索引。

-- 审计日志：列表按租户 + 时间倒序
DROP INDEX IF EXISTS idx_audit_created;
CREATE INDEX idx_audit_tenant_created ON audit_log (tenant_id, created_at DESC);

-- 告警记录：列表按租户 + 状态/时间；设备维度查询仍保留
DROP INDEX IF EXISTS idx_alarm_record_status;
CREATE INDEX idx_alarm_record_tenant_status
    ON alarm_record (tenant_id, status, triggered_at DESC);
CREATE INDEX idx_alarm_record_tenant_device
    ON alarm_record (tenant_id, device_id, triggered_at DESC);

-- 告警规则：按租户 + 产品评估
DROP INDEX IF EXISTS idx_alarm_rule_product;
CREATE INDEX idx_alarm_rule_tenant_product ON alarm_rule (tenant_id, product_id);

-- 设备命令：按租户 + 设备/状态
DROP INDEX IF EXISTS idx_command_device_created;
DROP INDEX IF EXISTS idx_command_status_sent;
CREATE INDEX idx_command_tenant_device
    ON device_command (tenant_id, device_id, created_at DESC);
CREATE INDEX idx_command_tenant_status
    ON device_command (tenant_id, status, sent_at);

-- 设备事件：按租户 + 设备 + 时间
DROP INDEX IF EXISTS idx_device_event_device_ts;
DROP INDEX IF EXISTS idx_device_event_created;
CREATE INDEX idx_device_event_tenant_device
    ON device_event (tenant_id, device_id, ts DESC);
CREATE INDEX idx_device_event_tenant_created
    ON device_event (tenant_id, created_at DESC);

-- 固件：产品列表按租户
DROP INDEX IF EXISTS idx_firmware_product;
CREATE INDEX idx_firmware_tenant_product ON firmware (tenant_id, product_id, created_at DESC);

-- 主机：空间查询按租户
DROP INDEX IF EXISTS idx_iot_host_space;
CREATE INDEX idx_iot_host_tenant_space ON iot_host (tenant_id, space_id);

-- 设备影子：租户 + 设备（device_id 本身全局唯一，保留原唯一索引不删）
CREATE INDEX idx_device_shadow_tenant ON device_shadow (tenant_id, device_id);

-- 设备：按租户 + 空间过滤
CREATE INDEX idx_device_tenant_space ON device (tenant_id, space_id);

-- OTA 任务：按租户 + 状态巡检 / 按租户 + 设备
DROP INDEX IF EXISTS idx_ota_task_status_updated;
DROP INDEX IF EXISTS idx_ota_task_device_created;
CREATE INDEX idx_ota_task_tenant_status
    ON ota_task (tenant_id, status, updated_at);
CREATE INDEX idx_ota_task_tenant_device
    ON ota_task (tenant_id, device_id, created_at DESC);

-- OTA 灰度：按租户 + 状态推进 / 按租户 + 项目列表
DROP INDEX IF EXISTS idx_ota_rollout_status;
DROP INDEX IF EXISTS idx_ota_rollout_project;
CREATE INDEX idx_ota_rollout_tenant_status
    ON ota_rollout (tenant_id, status, updated_at);
CREATE INDEX idx_ota_rollout_tenant_project
    ON ota_rollout (tenant_id, project_id, created_at DESC);

-- 空间：按租户 + 项目树
DROP INDEX IF EXISTS idx_space_project_parent;
CREATE INDEX idx_space_tenant_project_parent
    ON space (tenant_id, project_id, parent_id);

-- 物模型：原 (product_id) 检索由 (tenant_id, product_id) 覆盖
DROP INDEX IF EXISTS idx_thing_model_product;
