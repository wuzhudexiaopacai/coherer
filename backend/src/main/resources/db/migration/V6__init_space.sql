-- Coherer M2.5：空间（统一空间树：项目/区域/楼栋/楼层/房间）

CREATE TABLE space (
    id          BIGINT       PRIMARY KEY,
    tenant_id   VARCHAR(32)  NOT NULL DEFAULT 'default',
    parent_id   BIGINT,
    name        VARCHAR(64)  NOT NULL,
    code        VARCHAR(64),
    type        VARCHAR(16)  NOT NULL DEFAULT 'GENERIC',
    sort        INT          NOT NULL DEFAULT 0,
    description VARCHAR(255),
    status      VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted     SMALLINT     NOT NULL DEFAULT 0
);

COMMENT ON TABLE space IS '空间树：项目/区域/楼栋/楼层/房间等统一层级';
COMMENT ON COLUMN space.type IS 'PROJECT/AREA/BUILDING/FLOOR/ROOM/GENERIC';
COMMENT ON COLUMN space.parent_id IS '父空间，根节点为空';

CREATE INDEX idx_space_parent ON space (parent_id);
CREATE INDEX idx_space_tenant ON space (tenant_id);

-- 设备归属空间
ALTER TABLE device ADD COLUMN space_id BIGINT;
CREATE INDEX idx_device_space ON device (space_id);

-- 默认空间（固定 id=1，存量设备回填）
INSERT INTO space (id, tenant_id, parent_id, name, code, type, sort, description)
VALUES (1, 'default', NULL, '默认空间', 'default', 'PROJECT', 0, '系统默认空间');

UPDATE device SET space_id = 1 WHERE space_id IS NULL;
