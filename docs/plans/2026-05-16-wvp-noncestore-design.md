# WVP NonceStore 设计文档（遗留任务）

> Date: 2026-05-16
> Status: Backlog（不纳入迭代 2，后续迭代实现）
> Source: `2026-05-07-unified-terminal-identity-credential-design-v3.6.md` §12.1
> Priority: P2（安全加固 + 合规，非功能性阻塞）

---

## 1. 背景

WVP 当前 SIP Digest 认证流程中，`DigestServerAuthenticationHelper` 生成 nonce 发给终端、终端回传后参与 KD 计算，但 WVP 从不校验 nonce 是否由自己签发、是否过期、nc 是否递增。这意味着伪造 nonce、重放 REGISTER、使用过期 nonce 三种攻击在技术上可行。

当前未发生安全事故的原因是 WVP 与终端在同一内网，威胁模型较小。但等保三级合规审计和未来跨网段部署要求服务端 nonce 校验。

---

## 2. 需求

### 2.1 功能需求

| # | 需求 | 优先级 |
|---|------|--------|
| N1 | 签发 nonce 时写入 Redis（SETEX，TTL 300s） | P0 |
| N2 | 验证 nonce 时检查存在性 + nc 递增 | P0 |
| N3 | nonce 过期自动清除（依赖 Redis TTL） | P0 |
| N4 | Redis 故障三态降级状态机 | P0 |
| N5 | 心跳检测 Redis 可用性 | P0 |
| N6 | 恢复期强制重发 401 challenge | P1 |
| N7 | NonceStore 状态暴露为 metrics | P2 |

### 2.2 非功能需求

- 签发延迟：< 1ms（P99）
- 验证延迟：< 1ms（P99）
- 并发安全：多线程签发/验证无竞态
- 可部署：`@ConditionalOnProperty` 开关控制

---

## 3. 设计

### 3.1 Redis 数据结构

```
Key:    sip:nonce:{nonce}
Value:  JSON {"nc": 0, "issuedAt": 1715856000}
TTL:    300s
```

nc 字段记录当前已验证的最大 nonce count。每次验证通过后 nc 递增。

### 3.2 接口定义

```java
public interface NonceStore {
    /** 生成 nonce 并存入 Redis（generateChallenge 时调用） */
    String issueNonce();

    /** 验证 nonce 有效性 + nc 递增 */
    NonceValidationResult validateNonce(String nonce, int clientNc);

    /** 当前状态（供策略链判断是否可用） */
    NonceStoreState getState();
}
```

```java
public enum NonceValidationResult {
    VALID,            // nonce 存在 + nc 正确
    INVALID,          // nonce 不存在或已过期
    NC_MISMATCH,      // nc 不连续（重放攻击嫌疑）
    STORE_UNAVAILABLE // Redis 不可用
}
```

```java
public enum NonceStoreState {
    HEALTHY,            // 正常
    FAIL_CLOSED,        // Redis 不可用，拒绝所有 nonce 验证
    RECOVERY_CHALLENGE  // Redis 恢复后 30s 窗口，强制重发 challenge
}
```

### 3.3 签发流程（issueNonce）

```
1. nonce = UUID.randomUUID().toString().replace("-", "")
2. issuedAt = System.currentTimeMillis() / 1000
3. Redis SETEX sip:nonce:{nonce} 300 '{"nc": 0, "issuedAt": {issuedAt}}'
4. return nonce
```

### 3.4 验证流程（validateNonce）

```
1. GET sip:nonce:{nonce}
   - 不存在 → return INVALID（nonce 过期或伪造）
2. 解析 JSON → {nc: serverNc, issuedAt: ...}
3. if clientNc <= serverNc → return NC_MISMATCH（重放）
4. if clientNc != serverNc + 1 → return NC_MISMATCH（跳号）
5. Redis SETEX sip:nonce:{nonce} {剩余TTL} '{"nc": clientNc, ...}'
   （原子更新 nc，保留剩余 TTL）
6. return VALID
```

**nc 验证说明**：RFC 2617 要求 nc 从 00000001 开始递增。服务端校验 `clientNc == serverNc + 1` 确保每个 nonce count 只用一次。跳号视为异常（可能是中间人篡改或客户端 bug）。

### 3.5 三态状态机

```
状态转换图：

    心跳失败3次                  心跳恢复3次
  ┌────────────┐ ──────────→ ┌──────────────┐
  │  HEALTHY   │             │  FAIL_CLOSED  │
  │ 正常签发验证 │ ←────────── │ 拒绝所有验证   │
  └────────────┘             └──────────────┘
       ↑                            │
       │ 自动（30s后）                │ 心跳恢复3次
       │                            ↓
  ┌────────────┐             ┌──────────────────┐
  │  HEALTHY   │ ←────────── │ RECOVERY_CHALLENGE│
  └────────────┘  自动（30s） │ 强制重发 401      │
                              └──────────────────┘
```

| 状态 | 进入条件 | issueNonce | validateNonce | 退出条件 |
|------|---------|------------|---------------|---------|
| HEALTHY | Redis 心跳 OK | 正常签发 | 正常验证 | 心跳失败 3 次 |
| FAIL_CLOSED | 心跳失败 3 次（5s 窗口） | 仍签发（让终端有 nonce 可用） | **全部返回 STORE_UNAVAILABLE** | 心跳恢复 3 次 → RECOVERY |
| RECOVERY_CHALLENGE | 从 FAIL_CLOSED 退出 | 正常签发 | 全部返回 INVALID（强制终端重新协商） | 30 秒后 → HEALTHY |

**FAIL_CLOSED 期间仍签发 nonce 的原因**：Redis 恢复后终端需要有合法 nonce 可用。如果停止签发，恢复后所有终端都卡在无 challenge 状态。

**RECOVERY_CHALLENGE 强制重发的原因**：Redis 故障期间签发的 nonce 在 Redis 恢复后不存在（SET 失败了），故障前签发的 nonce 的 nc 状态也丢失。强制 30 秒重发窗口让所有终端重新协商，清除不一致状态。

### 3.6 心跳检测

```java
@Component
public class NonceStoreHealthChecker {
    private final StringRedisTemplate redis;
    private final NonceStoreImpl nonceStore;

    @Scheduled(fixedRate = 1000)
    public void checkHealth() {
        boolean alive;
        try {
            alive = Boolean.TRUE.equals(redis.getConnectionFactory()
                .getConnection().ping());
        } catch (Exception e) {
            alive = false;
        }
        nonceStore.recordHeartbeat(alive);
    }
}
```

`recordHeartbeat` 内部维护滑动窗口（最近 5 秒），连续 3 次失败 → FAIL_CLOSED，连续 3 次恢复 → RECOVERY_CHALLENGE。

### 3.7 与策略链集成

```java
// Ha1Strategy.authenticate()
public AuthResult authenticate(Device device, Request sipRequest, String realm) {
    if (device.getSipHa1() == null) return SKIP;

    // NonceStore 状态检查
    if (nonceStore.getState() == NonceStoreState.FAIL_CLOSED) {
        return FAIL;  // Redis 不可用，fail-closed 拒绝
    }

    // nonce 有效性校验
    String nonce = authHeader.getNonce();
    int clientNc = authHeader.getNonceCount();
    NonceValidationResult nv = nonceStore.validateNonce(nonce, clientNc);
    if (nv != NonceValidationResult.VALID) return FAIL;

    // nonce 有效，KD 验证
    return digestHelper.doAuthenticateHashedPassword(sipRequest, device.getSipHa1())
        ? AuthResult.SUCCESS : AuthResult.FAIL;
}
```

**过渡期容错**：PlaintextStrategy 和 GlobalPasswordStrategy 不依赖 NonceStore。Redis 故障时旧设备和监控级设备仍可注册。

### 3.8 与 generateChallenge 集成

```java
// DigestServerAuthenticationHelper.generateChallenge() 改造
public void generateChallenge(Response response, String realm) {
    String nonce;
    if (nonceStore != null && nonceStoreEnabled) {
        nonce = nonceStore.issueNonce();  // NonceStore 签发
    } else {
        nonce = generateRandomNonce();    // 原逻辑回退
    }
    // ... 构建 WWW-Authenticate header ...
}
```

### 3.9 配置项

```yaml
jxt:
  nonce-store:
    enabled: false               # 默认关闭，显式开启
    nonce-ttl-seconds: 300       # nonce 有效期 5 分钟
    heartbeat-interval-ms: 1000  # 心跳间隔
    heartbeat-failure-threshold: 3   # 连续失败次数 → FAIL_CLOSED
    heartbeat-recovery-threshold: 3  # 连续恢复次数 → RECOVERY
    recovery-duration-seconds: 30    # RECOVERY 窗口时长
```

---

## 4. 代码结构

```
com.genersoft.iot.vmp.jxt.identity/
└── auth/
    └── nonce/
        ├── NonceStore.java               // 接口
        ├── NonceStoreImpl.java           // Redis 实现 + 状态机 + 心跳记录
        ├── NonceValidationResult.java    // 验证结果枚举
        ├── NonceStoreState.java          // 状态枚举
        ├── NonceStoreHealthChecker.java  // Redis 心跳定时任务
        └── NonceStoreConfig.java         // @ConfigurationProperties
```

---

## 5. 测试要求

| 类型 | 覆盖范围 |
|------|---------|
| 单元测试 | issueNonce 写入 Redis + TTL；validateNonce 正常/INVALID/NC_MISMATCH/STORE_UNAVAILABLE |
| 状态机测试 | HEALTHY→FAIL_CLOSED→RECOVERY_CHALLENGE→HEALTHY 全路径转换 |
| 并发测试 | 多线程同时 validateNonce 同一 nonce，仅一个通过 |
| 集成测试 | 策略链 + NonceStore 联动：fail-closed 时 Ha1Strategy 返回 FAIL、PlaintextStrategy 不受影响 |
| 混沌测试 | Redis 连接断开/恢复/超时场景下的状态机行为 |

---

## 6. Redis 故障 SLA

| 维度 | 目标 | 处理 |
|------|------|------|
| Redis 不可用 P0 告警 | > 30 秒 | 立即告警运维 |
| 过渡期紧急回退 | > 5 分钟 | 运维评估切换 PlaintextStrategy |
| 过渡期容忍上限 | 5 分钟 | 超出即业务事故 |
| 阶段 3b 后容忍上限 | 0（Redis 不可用 = SIP 认证全停） | — |

---

## 7. 依赖

| 依赖 | 说明 |
|------|------|
| Redis | WVP 已集成 Redis（session/cache），无额外部署 |
| Ha1Strategy | NonceStore 为 Ha1Strategy 提供 nonce 校验，PlaintextStrategy 不依赖 |
| `@ConditionalOnProperty` | 开关控制，不影响未启用时的行为 |

---

## 8. 实现估时

| 工作项 | 工时 |
|--------|------|
| NonceStoreImpl + 状态机 | 2d |
| NonceStoreHealthChecker | 0.5d |
| 策略链集成 + generateChallenge 改造 | 1d |
| 单元测试 + 状态机测试 | 1d |
| 集成测试 + 混沌测试 | 1d |
| **合计** | **5.5d** |

---

## 9. 风险

| 风险 | 缓解 |
|------|------|
| Redis 延迟增加认证耗时 | nonce 操作是简单 GET/SETEX，P99 < 1ms |
| NonceStore 与现有 SIP 栈不兼容 | `@ConditionalOnProperty` 开关，关闭即回退原逻辑 |
| nc 递增在高并发下竞态 | 使用 Redis WATCH/MULTI 或 Lua 脚本保证原子性 |
| 大量 nonce 占用 Redis 内存 | TTL 300s 自动过期，1000 设备 × 5 分钟 = ~1000 key × ~100B ≈ 100KB |
