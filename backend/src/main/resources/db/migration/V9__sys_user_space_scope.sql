-- Coherer A3：用户数据范围（空间）
ALTER TABLE sys_user ADD COLUMN space_id BIGINT;

COMMENT ON COLUMN sys_user.space_id IS '数据范围：空间 ID（含子空间）；NULL 表示全部空间';
