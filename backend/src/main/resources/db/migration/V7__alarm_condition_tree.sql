-- Coherer M2.6：告警规则条件树（多条件 AND/OR + 恢复阈值）
-- 保留 legacy 列做兼容与展示，condition_tree 为求值真相源

ALTER TABLE alarm_rule ADD COLUMN condition_tree JSONB NOT NULL DEFAULT '{}'::jsonb;

UPDATE alarm_rule
SET condition_tree = jsonb_build_object(
    'logic', 'AND',
    'items', jsonb_build_array(
        jsonb_build_object(
            'property', property_identifier,
            'operator', operator,
            'threshold', threshold
        )
    )
)
WHERE condition_tree = '{}'::jsonb;

COMMENT ON COLUMN alarm_rule.condition_tree IS '条件树：{logic:AND/OR, items:[{property,operator,threshold,recoverThreshold}]}';
