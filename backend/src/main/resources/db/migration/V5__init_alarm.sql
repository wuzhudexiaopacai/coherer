-- Coherer M2：告警规则与告警记录

CREATE TABLE alarm_rule (
    id                  BIGINT       PRIMARY KEY,
    tenant_id           VARCHAR(32)  NOT NULL DEFAULT 'default',
    product_id          BIGINT       NOT NULL,
    name                VARCHAR(64)  NOT NULL,
    property_identifier VARCHAR(64)  NOT NULL,
    operator            VARCHAR(8)   NOT NULL,   -- GT/GE/LT/LE/EQ
    threshold           DOUBLE PRECISION NOT NULL,
    duration_seconds    INT          NOT NULL DEFAULT 0,
    level               VARCHAR(16)  NOT NULL DEFAULT 'WARNING',
    enabled             BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted             SMALLINT     NOT NULL DEFAULT 0
);

COMMENT ON TABLE alarm_rule IS '告警规则：属性 + 比较符 + 阈值 + 持续时长';
CREATE INDEX idx_alarm_rule_product ON alarm_rule (product_id);

CREATE TABLE alarm_record (
    id           BIGINT       PRIMARY KEY,
    tenant_id    VARCHAR(32)  NOT NULL DEFAULT 'default',
    rule_id      BIGINT       NOT NULL,
    product_id   BIGINT       NOT NULL,
    device_id    VARCHAR(64)  NOT NULL,
    level        VARCHAR(16)  NOT NULL,
    status       VARCHAR(16)  NOT NULL DEFAULT 'TRIGGERED', -- TRIGGERED/ACKNOWLEDGED/RECOVERED/CLOSED
    message      VARCHAR(255) NOT NULL,
    value        DOUBLE PRECISION,
    triggered_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    acked_at     TIMESTAMPTZ,
    recovered_at TIMESTAMPTZ,
    closed_at    TIMESTAMPTZ
);

COMMENT ON TABLE alarm_record IS '告警记录：触发/确认/恢复/关闭生命周期';
CREATE INDEX idx_alarm_record_device ON alarm_record (device_id, triggered_at DESC);
CREATE INDEX idx_alarm_record_status ON alarm_record (status);
