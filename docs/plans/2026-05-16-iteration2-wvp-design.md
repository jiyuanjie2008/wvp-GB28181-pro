# 迭代 2 WVP 线设计 — SIP 认证改造 + IAM 凭证同步

> Date: 2026-05-16
> Based on: `2026-05-07-unified-terminal-identity-credential-design-v3.6.md` §12.1–12.5
> Status: Draft
> Scope: 迭代 2 WVP 线（W01 + W04 Phase 1 + W05），W02 已完成，W03 NonceStore 移至后续迭代
> Approach: 方案 B — 先跑通 E2E，再完善

---

## 1. 设计决策记录

| 决策 | 选择 | 理由 |
|------|------|------|
| device_type 字段 | **不加** | IAM payload 不传 device_type；策略链按 sip_ha1 是否存在自动路由 |
| NonceStore | **移至后续迭代** | E2E 不阻塞；当前内网场景下重放风险低；独立设计文档已存档 |
| W05 端点范围 | **仅 POST register** | IAM 迭代 2 只做 register 推送；轮换/吊销端点等 IAM 迭代 3 |
| 策略链 Phase 1 | **仅 Ha1Strategy + PlaintextStrategy** | 够跑通 E2E；Disabled/Activated/GlobalPassword Phase 2 加 |
| 多租户 | **不考虑** | 当前单租户部署 |
| 幂等机制 | 用 IAM payload 的 idempotency_key | 无需额外设计 |
| WVP 新代码包 | `com.genersoft.iot.vmp.jxt.identity` | 遵循 §12.9 WVP Fork 维护策略 |
| IAM→WVP 认证 | **SM3+SM4 签名（query params）** | 复用 SignAuthenticationFilter，IAM SyncDispatcher 实现签名客户端 |

---

## 2. 功能清单

| # | 功能 | 简述 | 状态 | 工时 |
|---|------|------|------|------|
| W01 | SQL 迁移 | Device 表新增列 + 2 张新表 | 待做 | 1d |
| W02 | SIP Digest 认证修复 | doAuthenticateHashedPassword 补全 qop/nc | **已完成** (6a9415033) | 0d |
| W04 | 策略链 Phase 1 | Ha1Strategy + PlaintextStrategy | 待做 | 2d |
| W05 | DeviceIdentityController | POST /api/sy/device（register） | 待做 | 3d |
| — | E2E 集成验证 | 真实终端注册测试 | 待做 | 0.5d |
| ~~W03~~ | ~~NonceStore~~ | ~~Redis nonce + 三态状态机~~ | **移至后续迭代** | — |
| | | | **合计** | **6.5d** |

---

## 3. W01 数据库变更

### 3.1 迁移脚本

文件：`数据库/2.7.4/更新-mysql-2.7.4-jxt-device-identity.sql`

幂等模式，每个变更用存储过程包裹，通过 `information_schema.columns` 检查列/表是否存在。

### 3.2 Device 表新增列

```sql
-- 证据级终端凭证
ALTER TABLE wvp_device
  ADD COLUMN sip_ha1              VARCHAR(64) DEFAULT NULL COMMENT 'HA1摘要 = MD5(deviceId:realm:password)',
  ADD COLUMN sip_ha1_previous     VARCHAR(64) DEFAULT NULL COMMENT '轮换双发窗口：旧HA1',
  ADD COLUMN previous_valid_until DATETIME    DEFAULT NULL COMMENT '旧HA1过期时间',
  ADD COLUMN disabled             BOOLEAN     DEFAULT FALSE COMMENT '设备禁用标记',
  ADD COLUMN activated            BOOLEAN     DEFAULT TRUE  COMMENT '激活标记';

-- 索引
CREATE INDEX idx_wvp_device_disabled ON wvp_device(disabled);
CREATE INDEX idx_wvp_device_prev_expiry_cover ON wvp_device(previous_valid_until, sip_ha1_previous);
```

**设计决策**：不加 `device_type` 列。理由：
- IAM register payload 不包含 deviceType
- 策略链通过 `sip_ha1 IS NOT NULL` 区分证据级/监控级设备
- 现有设备全部 sip_ha1=NULL → 自动走 PlaintextStrategy → 过渡期兼容
- IAM 推送的新设备 sip_ha1 有值 → 自动走 Ha1Strategy

### 3.3 新增表

#### wvp_revocation_task（吊销异步任务队列）

迭代 3 使用，schema 先建。

```sql
CREATE TABLE IF NOT EXISTS wvp_revocation_task (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    device_id       VARCHAR(50) NOT NULL COMMENT '设备国标ID',
    task_type       VARCHAR(32) NOT NULL DEFAULT 'revoke' COMMENT '任务类型',
    status          VARCHAR(16) NOT NULL DEFAULT 'pending' COMMENT 'pending/running/completed/failed',
    attempts        INT NOT NULL DEFAULT 0,
    max_attempts    INT NOT NULL DEFAULT 3,
    last_error      TEXT,
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at    DATETIME,
    started_at      DATETIME,
    CONSTRAINT uq_revocation_task_pending UNIQUE (device_id, task_type, status)
);

CREATE INDEX idx_revocation_task_status ON wvp_revocation_task(status, created_at);
```

启动钩子需重置 stale running 状态：`UPDATE wvp_revocation_task SET status='pending' WHERE status='running'`。

#### wvp_realm_transition（Realm 变更双发历史）

迭代 3 使用，schema 先建。

```sql
CREATE TABLE IF NOT EXISTS wvp_realm_transition (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    device_id       VARCHAR(50) NOT NULL COMMENT '设备国标ID',
    old_realm       VARCHAR(64) NOT NULL,
    new_realm       VARCHAR(64) NOT NULL,
    valid_until     DATETIME NOT NULL COMMENT '旧realm fallback截止时间',
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_realm_transition_device ON wvp_realm_transition(device_id, valid_until);
```

#### wvp_callback_events

**已存在**（W07 IamCallbackClient 已实现）。无需创建。

### 3.4 Device.java 实体变更

在 `com.genersoft.iot.vmp.gb28181.bean.Device` 新增字段：

```java
// 证据级终端凭证
private String sipHa1;
private String sipHa1Previous;
private Date previousValidUntil;
private Boolean disabled = false;
private Boolean activated = true;
```

MyBatis 映射在 `DeviceMapper.xml` 的 resultMap 和 insert/update 语句中同步添加。

---

## 4. W05 DeviceIdentityController

### 4.1 端点定义

```
POST /api/sy/device?appKey={appKey}&accessToken={sm4Ciphertext}&timestamp={epochMillis}&sign={sm3Hex}
认证: SignAuthenticationFilter（SM3+SM4 签名，通过 URL query params 传递）
Content-Type: application/json
```

### 4.1a IAM→WVP 签名契约（SM3+SM4）

WVP 的 `SignAuthenticationFilter` 要求所有 `/api/sy/*` 请求携带以下 **URL query parameters**：

| 参数 | 类型 | 说明 |
|------|------|------|
| `appKey` | string | 应用标识，从 WVP Redis `SYSTEM_APPKEY` 获取 |
| `accessToken` | string | SM4-ECB 加密的 JSON token（hex 编码） |
| `timestamp` | long | 请求生成的 epoch 毫秒时间戳 |
| `sign` | string | SM3 签名（lowercase hex） |

**SM3 签名算法**：
1. 收集所有 query parameter（排除 `sign`）
2. 按 parameter name **字典序排列**
3. 拼接：`key1 + value1 + key2 + value2 + ...`
4. POST JSON 请求：**追加请求 body 原始字符串**
5. **追加 `secret`**（从 WVP Redis `SYSTEM_APPKEY` 对应的 appSecret）
6. 计算 `SM3(utf8Bytes)` → lowercase hex

**SM4 accessToken 生成**：
1. 明文：`{"expirationTime": <未来时间戳毫秒>}`
2. 密钥：WVP Redis `SYSTEM_SM4_KEY`（hex 编码的 128-bit key）
3. 算法：SM4-ECB，PKCS5 padding
4. 输出：hex 编码密文

**时间戳校验**：`currentTime > timestamp + expires * 60 * 1000` → 拒绝（code=3）。
`expires` 从 WVP Redis `sys_INTERFACE_VALID_TIME.systemValue` 获取（单位：分钟）。

**IAM 需要的配置**（从 WVP Redis 或共享配置获取）：
- `appKey` + `appSecret`（签名密钥对）
- `sm4Key`（accessToken 加密密钥）
- `expires`（时间戳有效期，分钟）

**响应判断**：WVP 始终返回 HTTP 200，通过 JSON body 的 `code` 字段判断：
- `code == 0`：成功
- `code != 0`：失败（`msg` 字段有原因）

### 4.2 请求体

来自 IAM SyncDispatcher 的 register payload：

```json
{
  "schema_version": 1,
  "idempotency_key": "iam-reg-123-abc456",
  "trace_id": "00-abc456...-01",
  "tenant_id": 1,
  "target_deviceId": "34020000001320000001",
  "operation": "register",
  "occurred_at": "2026-05-16T10:00:00Z",
  "payload_specific": {
    "deviceName": "执法仪001",
    "sipHa1": "a1b2c3d4e5f6...",
    "realm": "3502000000",
    "charset": "GB2312",
    "streamMode": "TCP-PASSIVE",
    "sdpIp": "192.168.1.100",
    "mediaServerId": "auto",
    "ssrcCheck": false,
    "geoCoordSys": "WGS84",
    "heartbeatInterval": 60,
    "heartbeatCount": 3
  }
}
```

### 4.3 输入校验

| 字段 | 规则 | 错误码 |
|------|------|--------|
| schema_version | 必须为 1 | 13001 |
| operation | 必须为 "register" | 13002 |
| target_deviceId | 20 位数字 | 13003 |
| sipHa1 | 32 位或 64 位 hex | 13004 |
| realm | 与 sipConfig.domain 一致 | 13005 |
| idempotency_key | 非空 | 13006 |
| payload_specific.sipHa1 | 非空 | 13007 |
| payload_specific.realm | 非空 | 13008 |

### 4.4 处理逻辑

```
POST /api/sy/device
  ↓
1. 输入校验（字段格式 + realm 一致性）
   ↓
2. 幂等检查（idempotency_key）
   - 用 key 查 wvp_idempotency_log 表（或内存缓存）
   - 已存在且成功 → 直接返回 200 {"created": false}
   - 已存在但失败 → 重新处理
   ↓
3. 查找设备（target_deviceId → wvp_device.device_id）
   ↓
   设备不存在:
     INSERT wvp_device
       device_id    = target_deviceId
       name         = deviceName
       sip_ha1      = sipHa1
       transport    = streamMode.toLowerCase()
       charset      = charset
       mediaServerId = mediaServerId
       ssrcCheck    = ssrcCheck
       geoCoordSys  = geoCoordSys
       asMessageChannel = asMessageChannel
       heartbeatInterval = heartbeatInterval
       heartbeatCount    = heartbeatCount
       disabled     = false
       activated    = true
       password     = null
       expires      = 3600  -- 占位值（原始秒数），终端 REGISTER 时由 SIP Expires header 覆盖
   ↓
   设备已存在:
     UPDATE wvp_device SET
       sip_ha1      = sipHa1
       name         = COALESCE(deviceName, name)
       charset      = COALESCE(charset, charset)
       mediaServerId = COALESCE(mediaServerId, mediaServerId)
       -- 其他终端属性按需更新（仅非 null 字段）
     WHERE device_id = target_deviceId
   ↓
4. 记录幂等 key（INSERT wvp_idempotency_log）
   ↓
5. 返回 200
```

### 4.5 响应格式

成功（新创建）：
```json
{
  "code": 0,
  "msg": "success",
  "data": {
    "deviceId": "34020000001320000001",
    "created": true
  }
}
```

成功（幂等命中）：
```json
{
  "code": 0,
  "msg": "success",
  "data": {
    "deviceId": "34020000001320000001",
    "created": false
  }
}
```

校验失败：
```json
{
  "code": 13004,
  "msg": "Invalid sipHa1 format: expected 32 or 64 hex chars"
}
```

realm 不匹配：
```json
{
  "code": 13005,
  "msg": "Realm mismatch: expected '3502000000', got 'other'"
}
```

### 4.6 幂等机制

使用独立轻量表记录已处理的 idempotency_key：

```sql
CREATE TABLE IF NOT EXISTS wvp_idempotency_log (
    idempotency_key VARCHAR(128) PRIMARY KEY,
    operation       VARCHAR(32) NOT NULL,
    device_id       VARCHAR(50) NOT NULL,
    status          VARCHAR(16) NOT NULL DEFAULT 'success',
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

- INSERT 成功 = 首次处理
- INSERT 失败（唯一约束冲突）= 幂等命中，查询已有结果返回

定期清理：`DELETE FROM wvp_idempotency_log WHERE created_at < NOW() - INTERVAL 7 DAY`

### 4.7 代码结构

```
com.genersoft.iot.vmp.jxt.identity/
├── controller/
│   └── DeviceIdentityController.java    // POST /api/sy/device
├── dto/
│   ├── IamSyncRequest.java              // 顶层请求 DTO
│   └── IamSyncPayloadSpecific.java      // payload_specific DTO
├── service/
│   └── DeviceIdentityService.java       // 设备创建/更新 + 幂等
└── mapper/
    └── DeviceIdentityMapper.java        // MyBatis 设备写入
```

---

## 5. W04 策略链 Phase 1

### 5.1 策略接口

```java
public interface DeviceAuthStrategy {
    int priority();
    AuthResult authenticate(Device device, Request sipRequest, String realm);
}
```

```java
public enum AuthResult {
    SUCCESS,  // 认证通过
    FAIL,     // 认证失败
    SKIP      // 本策略不适用
}
```

### 5.2 Phase 1 策略（2 条）

```
Ha1Strategy(priority=1) → PlaintextStrategy(priority=2)
```

**Ha1Strategy**：

```
输入: Device, SIP Request, realm
  ↓
if device.sipHa1 == null → SKIP
  ↓
调用 DigestServerAuthenticationHelper.doAuthenticateHashedPassword(request, device.sipHa1)
  成功 → SUCCESS
  失败 → FAIL
```

**PlaintextStrategy**（过渡期兼容旧设备）：

```
输入: Device, SIP Request, realm
  ↓
password = device.getPassword()
if password == null || password.isEmpty() → SKIP
  ↓
调用 DigestServerAuthenticationHelper.doAuthenticatePlainTextPassword(request, password)
  成功 → SUCCESS
  失败 → FAIL
```

### 5.3 策略链调度器

```java
@Component
public class DeviceAuthStrategyChain {

    private final List<DeviceAuthStrategy> strategies;

    public DeviceAuthStrategyChain(List<DeviceAuthStrategy> strategies) {
        this.strategies = strategies.stream()
            .sorted(Comparator.comparingInt(DeviceAuthStrategy::priority))
            .toList();
    }

    public AuthResult authenticate(Device device, Request sipRequest, String realm) {
        for (DeviceAuthStrategy strategy : strategies) {
            AuthResult result = strategy.authenticate(device, sipRequest, realm);
            if (result != AuthResult.SKIP) {
                return result;
            }
        }
        return AuthResult.FAIL;
    }
}
```

**语义**：第一条匹配的策略给出最终结果（SUCCESS 或 FAIL），后续策略不执行。

### 5.4 RegisterRequestProcessor 改造

当前认证逻辑（`RegisterRequestProcessor.java`）：

```java
// 现有代码
String password = device.getPassword();
if (password == null) password = sipConfig.getPassword();
boolean authorized = digestHelper.doAuthenticatePlainTextPassword(request, password);
```

改为：

```java
// 新代码
AuthResult result = strategyChain.authenticate(device, request, sipConfig.getDomain());

boolean authorized;
if (result == AuthResult.SUCCESS) {
    authorized = true;
} else if (result == AuthResult.SKIP) {
    // 所有策略都 SKIP：设备无 HA1 也无 password
    String globalPassword = sipConfig.getPassword();
    if (ObjectUtils.isEmpty(globalPassword)) {
        // 无鉴权模式：与现有行为一致（RegisterRequestProcessor:167）
        // 当 password 和全局密码都为空时，直接放行
        authorized = true;
    } else {
        authorized = digestHelper.doAuthenticatePlainTextPassword(request, globalPassword);
    }
} else {
    // FAIL：有凭证但验证失败 → 拒绝
    authorized = false;
}
```

**关键行为变化**：

| 场景 | 现有行为 | 改造后 |
|------|---------|--------|
| IAM 推送的设备（有 sipHa1，无 password） | 用全局密码验证 → **失败** | Ha1Strategy 验证 → **成功** |
| 旧设备（无 sipHa1，有 password） | 用 device password 验证 → 成功 | PlaintextStrategy 验证 → 成功 |
| 监控级设备（无 sipHa1，无 password，有全局密码） | 用全局密码 → 成功 | SKIP → 兜底全局密码 → 成功 |
| 无鉴权设备（无 sipHa1，无 password，无全局密码） | 直接放行 → 成功 | SKIP → 全局密码为空 → 放行 → 成功 |
| 禁用设备（未来） | 正常验证 | DisabledStrategy → FAIL（Phase 2） |

### 5.5 代码结构

```
com.genersoft.iot.vmp.jxt.identity/
└── auth/
    ├── DeviceAuthStrategy.java          // 策略接口
    ├── AuthResult.java                  // 结果枚举
    ├── DeviceAuthStrategyChain.java     // 策略链调度
    ├── Ha1Strategy.java                 // HA1 策略
    └── PlaintextStrategy.java           // 明文策略（过渡期）
```

---

## 6. 实现顺序（方案 B）

```
Step 1: W01 SQL 迁移 + Device.java 实体变更           [Day 1]
  ↓
Step 2: W05 DeviceIdentityController (POST register)  [Day 2-4]
  ↓ 可并行启动 IAM 推送，验证 WVP 能接收并写入 sip_ha1
Step 3: W04 策略链 (Ha1Strategy + PlaintextStrategy)  [Day 4-5]
  ↓
Step 4: RegisterRequestProcessor 改造                  [Day 5-6]
  ↓
Step 5: E2E 集成验证（真实终端注册）                    [Day 6-7]
  ↓
Step 6: 修复 E2E 中发现的问题                          [Day 7]
```

**Step 2 完成后即可与 IAM 线联调**（IAM 推送 → WVP 写入 → 确认 sip_ha1 存在），无需等策略链完成。

---

## 7. E2E 验证流程

### 7.1 前置条件

- IAM 迭代 2 已部署（SyncDispatcher 可用）
- WVP SQL 迁移已执行
- ZX 真实终端可达

### 7.2 验证步骤

```
1. IAM 侧：POST /api/v1/equipment/bwc（创建 5G 执法仪 + 生成凭证）
   → 预期：IAM 返回 syncStatus=registered

2. WVP 侧：确认 wvp_device 表中 sip_ha1 列有值
   → SELECT device_id, sip_ha1 FROM wvp_device WHERE device_id = '{deviceId}'

3. ZX 终端：发起 SIP REGISTER
   → 预期：WVP Ha1Strategy 验证通过 → 200 OK → 设备上线

4. WVP → IAM：DeviceOnlineEvent 回调
   → 预期：IAM 收到回调，设备状态更新为 online

5. 验证旧设备不受影响：普通摄像头 REGISTER
   → 预期：PlaintextStrategy 或全局密码验证通过 → 正常上线
```

### 7.3 回归验证

| 场景 | 预期结果 |
|------|---------|
| IAM 推送的设备 REGISTER | Ha1Strategy SUCCESS |
| 旧设备（有 password）REGISTER | PlaintextStrategy SUCCESS |
| 监控级设备 REGISTER | 全局密码 SUCCESS |
| 错误密码 REGISTER | 策略链 FAIL → 401 |
| WVP 重启后旧设备注册 | 不受影响 |

---

## 8. 配置项

```yaml
jxt:
  identity:
    enabled: true                        # WVP 设备身份功能总开关
    controller:
      enabled: true                      # DeviceIdentityController 开关
    strategy:
      ha1-enabled: true                  # Ha1Strategy 开关
      plaintext-enabled: true            # PlaintextStrategy 开关（过渡期）
    idempotency:
      cleanup-days: 7                    # 幂等日志保留天数
```

所有新功能通过 `@ConditionalOnProperty` 开关控制，关闭后完全回退到现有行为。

---

## 9. 回滚方案

### L1 软回退（可逆无损）

1. 设置 `jxt.identity.enabled=false` → 所有新功能关闭
2. WVP 回退到原始 RegisterRequestProcessor 行为（用 password + 全局密码）
3. 新增列/表保留，不影响旧逻辑

### L2 硬回退（不可逆，需变更单）

1. 备份数据库
2. DROP 新增表 + ALTER TABLE DROP 新增列
3. 接受后果：IAM 推送的设备 sip_ha1 数据丢失

---

## 10. Phase 2 遗留项（后续迭代）

| 功能 | 简述 | 设计文档 |
|------|------|---------|
| NonceStore | Redis nonce 校验 + 三态降级 | `2026-05-16-wvp-noncestore-design.md` |
| DisabledStrategy | device.disabled → 拒绝认证 | §12.2 |
| NotActivatedStrategy | device.activated=false → 拒绝 | §12.2 |
| GlobalPasswordStrategy | 全局密码兜底（独立策略） | §12.2 |
| Ha1PreviousStrategy | 轮换双发窗口旧 HA1 | 迭代 3 |
| RealmFallbackStrategy | Realm 变更 24h fallback | 迭代 3 |
| PUT credential / DELETE previous | 轮换相关端点 | 迭代 3 |
| DELETE device | 吊销端点 | 迭代 3 |
| Ha1MigrationRunner | 旧设备 password → HA1 迁移 | 迭代 3 |
| RevocationWorker | 吊销任务消费 + SIP BYE | 迭代 3 |
