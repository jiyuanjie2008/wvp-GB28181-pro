-- JXT: WVP→IAM 回调事件表（设备身份集成 spec §3.3.2）
-- 用于幂等回调、重试、dead-letter 队列

CREATE TABLE IF NOT EXISTS wvp_callback_events (
    event_id        VARCHAR(26) PRIMARY KEY COMMENT 'ULID (26 chars)',
    event_type      VARCHAR(32) NOT NULL COMMENT 'device.online / offline / register-failed / revoked-rejected',
    device_id       VARCHAR(20) NOT NULL,
    payload_json    TEXT COMMENT '完整 payload',
    sent_at         DATETIME NOT NULL COMMENT 'WVP 发送时间',
    acked_at        DATETIME COMMENT 'IAM 成功 ACK 时间',
    ack_attempts    INT NOT NULL DEFAULT 0 COMMENT '发送尝试次数',
    last_http_code  INT COMMENT '最近一次 HTTP 响应码',
    status          VARCHAR(16) NOT NULL DEFAULT 'pending' COMMENT 'pending/acked/dead_letter'
);
CREATE INDEX idx_wvp_callback_status_sent ON wvp_callback_events(status, sent_at);
CREATE INDEX idx_wvp_callback_device ON wvp_callback_events(device_id);
