-- 修正存量默认项目名称：V22 从根空间继承为“默认空间”，项目语义应为“默认项目”
UPDATE project
SET name = '默认项目',
    updated_at = now()
WHERE id = 1
  AND code = 'default'
  AND name = '默认空间';
