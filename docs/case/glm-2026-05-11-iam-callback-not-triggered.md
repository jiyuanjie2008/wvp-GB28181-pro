# IAM 回调未触发案例分析

## 现象

设备 `35020000201311008877` 成功完成 SIP 注册并上线，但 WVP 未向 IAM 服务发送设备上线回调（HTTP POST），IAM 侧无感知。

## 背景

WVP 在设备注册成功后会通过 Spring ApplicationEvent 机制发布 `DeviceOnlineEvent`，由 `CallbackEventListener` 异步监听并调用 `IamCallbackClient` 向 IAM 发送 HTTP 回调。该回调链路用于将 WVP 侧的设备状态变更通知到 IAM（安全管理系统），实现跨服务设备状态同步。

## 根因分析

### 调用链追踪

```
RegisterRequestProcessor.process()  (line 247)
  → eventPublisher.deviceOnlineEventPublish(device)
    → Spring publishEvent: DeviceOnlineEvent
      → CallbackEventListener.onDeviceOnline()       @Async @EventListener, 已执行
        → iamCallbackClient.sendCallback("online", deviceId, payload)  已调用
          → config.isEnabled() == false               ← 在此直接 return，回调未发出
```

### 关键代码

`IamCallbackClient.sendCallback()` (line 37-40):

```java
@Async
public void sendCallback(String eventType, String deviceId, Map<String, Object> payload) {
    if (!config.isEnabled()) {
        return;  // ← 未配置时静默退出，无日志、无异常
    }
    // ... 后续 HTTP 请求逻辑未执行
}
```

`IamCallbackConfig.isEnabled()` (line 24-27):

```java
public boolean isEnabled() {
    return primaryKey != null && !primaryKey.isEmpty()
            && iamBaseUrl != null && !iamBaseUrl.isEmpty();
}
```

`isEnabled()` 要求同时满足两个配置项：
- `jxt.iam-callback.primary-key` — 回调签名密钥
- `jxt.iam-callback.iam-base-url` — IAM 服务地址

### 当前配置状态

| 检查项 | 结果 |
|--------|------|
| `application-docker.yml` 中 `jxt.iam-callback` 配置 | **不存在** |
| `docker-compose.yml` 中相关环境变量 | **不存在** |
| 数据库 `wvp_callback_events` 表 | **未创建**（建表 SQL 未执行） |
| 容器日志 `[IAM回调]` 输出 | **无任何输出** |

三个条件全部缺失，`isEnabled()` 返回 `false`，`sendCallback()` 在第一行就 return 了。不会产生任何日志或异常，从外部观察就是"什么都没发生"。

## 日志证据

WVP 容器日志确认注册成功，但无回调相关输出：

```
# 注册成功 ✓
2026-05-11 00:05:42.311 [task-7]  INFO --- RegisterRequestProcessor: 243 [注册成功] deviceId: 35020000201311008877->172.23.0.1:37622
2026-05-11 00:05:42.312 [task-7]  INFO --- DeviceServiceImpl: 307 [设备上线] deviceId：35020000201311008877->172.23.0.1:37622
2026-05-11 00:05:42.343 [task-7]  INFO --- RedisCatchStorageImpl: 425 [redis通知] 推送设备/通道状态-> 35020000201311008877 ON

# IAM 回调相关日志 — 无（预期应有 [IAM回调] 前缀的日志）
```

## 修复步骤

### 1. 创建回调事件表

在 WVP 使用的 MySQL 数据库中执行：

```bash
docker exec -i docker-polaris-mysql-1 mysql -uwvp_user -pwvp_password wvp \
  < src/main/resources/sql/2.7.4-jxt-callback-events.sql
```

建表 SQL 内容（`src/main/resources/sql/2.7.4-jxt-callback-events.sql`）：

```sql
CREATE TABLE IF NOT EXISTS wvp_callback_events (
    event_id        VARCHAR(26) PRIMARY KEY,
    event_type      VARCHAR(32) NOT NULL,
    device_id       VARCHAR(20) NOT NULL,
    payload_json    TEXT,
    sent_at         DATETIME NOT NULL,
    acked_at        DATETIME,
    ack_attempts    INT NOT NULL DEFAULT 0,
    last_http_code  INT,
    status          VARCHAR(16) NOT NULL DEFAULT 'pending'
);
CREATE INDEX idx_wvp_callback_status_sent ON wvp_callback_events(status, sent_at);
CREATE INDEX idx_wvp_callback_device ON wvp_callback_events(device_id);
```

### 2. 添加 IAM 回调配置

在 `docker/wvp/wvp/application-docker.yml` 末尾添加：

```yaml
jxt:
  iam-callback:
    primary-key: <与 IAM 约定的签名密钥>
    secondary-key: <备选密钥，可选>
    active-key-version: 1
    iam-base-url: http://<iam-service-host>:<iam-service-port>
```

或者通过 `docker-compose.yml` 的 environment 传递（Spring Boot relaxed binding）：

```yaml
environment:
  JXT_IAM_CALLBACK_PRIMARY_KEY: "<与 IAM 约定的签名密钥>"
  JXT_IAM_CALLBACK_IAM_BASE_URL: "http://<iam-service-host>:<iam-service-port>"
```

### 3. 重启 WVP

```bash
cd docker
docker-compose restart polaris-wvp
```

### 4. 验证

设备重新注册后，检查：

```bash
# 查看回调日志
docker logs docker-polaris-wvp-1 2>&1 | grep "IAM回调"

# 预期成功输出
# [IAM回调] 成功: eventType=online, deviceId=35020000201311008877

# 预期失败输出（IAM 不可达时）
# [IAM回调] 网络错误: eventType=online, deviceId=35020000201311008877, attempts=1

# 查询回调事件记录
docker exec docker-polaris-mysql-1 mysql -uwvp_user -pwvp_password wvp \
  -e "SELECT event_id, event_type, device_id, status, ack_attempts, last_http_code, sent_at FROM wvp_callback_events ORDER BY sent_at DESC LIMIT 10;"
```

## 回调行为说明

配置生效后，WVP 在每次设备上线/下线时会：

1. 生成回调事件（ULID event_id），插入 `wvp_callback_events` 表（status=pending）
2. 发送 HTTP POST 到 `{iam-base-url}/api/v1/wvp-callback/device/online`（或 offline）
3. 请求头携带 `X-WVP-Callback-Key`、`X-WVP-Callback-Timestamp`、`X-WVP-Callback-Nonce` 用于 IAM 验签
4. IAM 返回 2xx → status 更新为 acked
5. IAM 返回非 2xx 或网络错误 → ack_attempts +1，达到 5 次后标记为 dead_letter
