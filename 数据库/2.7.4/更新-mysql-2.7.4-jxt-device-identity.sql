-- ============================================================
-- WVP 迭代 2: 设备身份 + 凭证同步
-- Date: 2026-05-16
-- Based on: 2026-05-16-iteration2-wvp-design.md §3
-- ============================================================

DELIMITER //

-- 1. Device 表新增列
CREATE PROCEDURE `wvp_device_identity_columns`()
BEGIN
    -- sip_ha1: HA1 摘要
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE TABLE_SCHEMA = (SELECT DATABASE()) AND table_name = 'wvp_device' AND column_name = 'sip_ha1') THEN
        ALTER TABLE wvp_device ADD COLUMN sip_ha1 VARCHAR(64) DEFAULT NULL COMMENT 'HA1摘要 = MD5(deviceId:realm:password)';
    END IF;

    -- disabled: 设备禁用标记
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE TABLE_SCHEMA = (SELECT DATABASE()) AND table_name = 'wvp_device' AND column_name = 'disabled') THEN
        ALTER TABLE wvp_device ADD COLUMN disabled BOOLEAN DEFAULT FALSE COMMENT '设备禁用标记';
    END IF;

    -- activated: 激活标记
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE TABLE_SCHEMA = (SELECT DATABASE()) AND table_name = 'wvp_device' AND column_name = 'activated') THEN
        ALTER TABLE wvp_device ADD COLUMN activated BOOLEAN DEFAULT TRUE COMMENT '激活标记';
    END IF;
END; //

CALL wvp_device_identity_columns();
DROP PROCEDURE wvp_device_identity_columns;

-- 2. 索引
CREATE PROCEDURE `wvp_device_identity_indexes`()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                   WHERE TABLE_SCHEMA = (SELECT DATABASE()) AND table_name = 'wvp_device' AND index_name = 'idx_wvp_device_disabled') THEN
        CREATE INDEX idx_wvp_device_disabled ON wvp_device(disabled);
    END IF;
END; //

CALL wvp_device_identity_indexes();
DROP PROCEDURE wvp_device_identity_indexes;

-- 3. wvp_idempotency_log（幂等日志）
CREATE TABLE IF NOT EXISTS wvp_idempotency_log (
    idempotency_key VARCHAR(128) PRIMARY KEY,
    operation       VARCHAR(32) NOT NULL,
    device_id       VARCHAR(50) NOT NULL,
    status          VARCHAR(16) NOT NULL DEFAULT 'success',
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE PROCEDURE `wvp_idempotency_log_indexes`()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                   WHERE TABLE_SCHEMA = (SELECT DATABASE()) AND table_name = 'wvp_idempotency_log' AND index_name = 'idx_wvp_idempotency_log_created_at') THEN
        CREATE INDEX idx_wvp_idempotency_log_created_at ON wvp_idempotency_log(created_at);
    END IF;
END; //

CALL wvp_idempotency_log_indexes();
DROP PROCEDURE wvp_idempotency_log_indexes;

DELIMITER ;
