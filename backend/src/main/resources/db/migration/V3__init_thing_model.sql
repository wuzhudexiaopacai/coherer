-- Coherer M2：物模型（属性/事件/服务定义）

CREATE TABLE thing_model (
    id         BIGINT       PRIMARY KEY,
    product_id BIGINT       NOT NULL,
    type       VARCHAR(16)  NOT NULL,              -- PROPERTY / EVENT / SERVICE
    identifier VARCHAR(64)  NOT NULL,
    name       VARCHAR(64)  NOT NULL,
    data_type  VARCHAR(16),                        -- INT / FLOAT / DOUBLE / BOOL / STRING
    access_mode VARCHAR(8)  NOT NULL DEFAULT 'R',  -- R / RW
    unit       VARCHAR(16),
    spec       JSONB        NOT NULL DEFAULT '{}'::jsonb,  -- min/max 或事件/服务参数定义
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted    SMALLINT     NOT NULL DEFAULT 0
);

COMMENT ON TABLE thing_model IS '产品物模型：属性、事件、服务定义';
COMMENT ON COLUMN thing_model.spec IS '扩展定义：{"min":0,"max":100} 或事件/服务参数';

CREATE UNIQUE INDEX uk_thing_model ON thing_model (product_id, type, identifier) WHERE deleted = 0;
CREATE INDEX idx_thing_model_product ON thing_model (product_id);
