-- Coherer：项目层（项目 → 空间 → 主机/设备）
-- 兼容存量：每个根 PROJECT 空间升级为项目，其余空间归默认项目。

CREATE TABLE project (
    id          BIGINT       PRIMARY KEY,
    tenant_id   VARCHAR(32)  NOT NULL DEFAULT 'default',
    code        VARCHAR(64)  NOT NULL,
    name        VARCHAR(64)  NOT NULL,
    status      VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    description VARCHAR(255),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted     SMALLINT     NOT NULL DEFAULT 0
);

COMMENT ON TABLE project IS '项目：空间/主机/设备的顶层业务分组';
CREATE UNIQUE INDEX uk_project_code ON project (tenant_id, code) WHERE deleted = 0;

-- 1) 根 PROJECT 空间升级为项目（项目 id 复用根空间 id，便于无损迁移）
INSERT INTO project (id, tenant_id, code, name, status, description, created_at, updated_at)
SELECT s.id,
       COALESCE(s.tenant_id, 'default'),
       CASE WHEN s.id = 1 THEN 'default'
            ELSE LEFT(COALESCE(NULLIF(s.code, ''), 'project'), 48) || '-' || s.id END,
       s.name,
       COALESCE(s.status, 'ACTIVE'),
       s.description,
       COALESCE(s.created_at, now()),
       COALESCE(s.updated_at, now())
FROM space s
WHERE s.parent_id IS NULL AND s.type = 'PROJECT' AND s.deleted = 0
ON CONFLICT (id) DO NOTHING;

-- 2) 确保默认项目存在（即使没有 id=1 的根空间）
INSERT INTO project (id, tenant_id, code, name, status, description, created_at, updated_at)
VALUES (1, 'default', 'default', '默认项目', 'ACTIVE', '存量空间默认归属', now(), now())
ON CONFLICT (id) DO NOTHING;

-- 3) 为每个空间找到根祖先；根是 PROJECT 且已迁移为项目时归属该项目，否则默认项目
ALTER TABLE space ADD COLUMN project_id BIGINT;

WITH RECURSIVE roots AS (
    SELECT id, id AS root_id, type
    FROM space
    WHERE parent_id IS NULL
    UNION ALL
    SELECT child.id, roots.root_id, roots.type
    FROM space child
    JOIN roots ON child.parent_id = roots.id
)
UPDATE space s
SET project_id = CASE
        WHEN roots.type = 'PROJECT'
             AND EXISTS (SELECT 1 FROM project p WHERE p.id = roots.root_id)
        THEN roots.root_id
        ELSE 1
    END
FROM roots
WHERE roots.id = s.id;

UPDATE space SET project_id = 1 WHERE project_id IS NULL;
ALTER TABLE space ALTER COLUMN project_id SET NOT NULL;
ALTER TABLE space ALTER COLUMN project_id SET DEFAULT 1;
CREATE INDEX idx_space_project_parent ON space (project_id, parent_id);

-- 根 PROJECT 空间迁移后改为普通根空间，避免“项目/空间”双重语义
UPDATE space SET type = 'GENERIC'
WHERE parent_id IS NULL AND type = 'PROJECT';

-- 4) OTA 灰度批次增加项目归属，历史批次归默认项目
ALTER TABLE ota_rollout ADD COLUMN project_id BIGINT NOT NULL DEFAULT 1;
CREATE INDEX idx_ota_rollout_project ON ota_rollout (project_id, created_at DESC);
COMMENT ON COLUMN ota_rollout.project_id IS '批次创建时所属项目';
