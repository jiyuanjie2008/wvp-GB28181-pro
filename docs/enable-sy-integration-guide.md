# 启用 WVP sy.enable 集成指南

本文档指导如何启用 WVP 的 `sy.enable` 开关，使 security-management（IAM）能够通过 SM3+SM4 签名认证将执法仪 GB28181 终端身份和凭证同步到 WVP。

## 1. 概述

### 1.1 数据流

```
security-management (IAM)                    WVP-GB28181-Pro
        │                                          │
        │  1. 创建执法仪 → 生成 OutboundSyncTask    │
        │                                          │
        │  2. SyncDispatcher 后台 Worker            │
        │     从队列领取 pending 任务               │
        │                                          │
        │  3. SM3+SM4 签名                          │
        │     POST {apiUrl}/api/sy/device           │
        ├─────────────────────────────────────────► │  SignAuthenticationFilter 校验
        │                                          │  DeviceIdentityController 处理
        │                                          │  写入 Device 表 + Redis 缓存
        │                                          │
        │  4. 成功响应 {"code":0}                    │
        │◄─────────────────────────────────────────┤
        │                                          │
        │  5. 标记 PlatformCredential 为 Active     │
```

### 1.2 sy.enable 门控范围

`sy.enable` 是 WVP 的"第三方平台对接"总开关，启用后会激活以下 Bean：

| Bean | 路径 | 功能 |
|------|------|------|
| `SignAuthenticationFilter` | `/api/sy/*` | SM3+SM4 签名校验 |
| `CameraChannelController` | `/api/sy/camera/*` | 第三方摄像头查询、点播、PTZ |
| `CameraChannelService` | - | Redis 消息订阅、GPS/通道/状态处理 |
| `SyServiceImpl` (IMapService) | `/api/server/map/*` | 地图配置和图标 |
| `DeviceIdentityController` | `/api/sy/device` | IAM 设备身份同步 |

**不受影响的组件**：

- ZLM Webhook (`/index/hook/*`) — 始终可用
- 标准设备管理 (`/api/device/*`) — 始终可用
- 视频点播 (`/api/play/*`) — 始终可用
- SIP 信令 — 始终可用

## 2. 配置步骤

### 2.1 生成密钥

```bash
# 生成 SM4 密钥（16 字节 = 32 字符 hex）
SM4_KEY=$(openssl rand -hex 16)
echo "SM4_KEY: $SM4_KEY"

# 生成 appKey
APP_KEY=$(openssl rand -hex 16)
echo "APP_KEY: $APP_KEY"

# 生成 appSecret
APP_SECRET=$(openssl rand -hex 32)
echo "APP_SECRET: $APP_SECRET"

# 生成 adminToken（WVP 端管理绕过 token，Go 端不使用）
ADMIN_TOKEN=$(openssl rand -hex 32)
echo "ADMIN_TOKEN: $ADMIN_TOKEN"
```

记录以上输出值，后续步骤需要使用。

### 2.2 WVP Redis 写入凭证

WVP 的 `CameraChannelService` 在启动时从 Redis 读取 4 个 Key。**必须在 WVP 启动前写入**。

```bash
# 连接到 WVP 使用的 Redis 实例
redis-cli -h <redis-host> -p <redis-port> -a <redis-password> -n <redis-db>

# 写入 SM4 密钥（纯字符串）
SET SYSTEM_SM4_KEY "<上面生成的 SM4_KEY>"

# 写入 appKey + appSecret（JSON 对象）
SET SYSTEM_APPKEY '{"appKey":"<上面生成的 APP_KEY>","appSecret":"<上面生成的 APP_SECRET>"}'

# 写入过期时间（JSON 对象，单位：分钟）
SET sys_INTERFACE_VALID_TIME '{"systemValue":30}'

# 写入管理绕过 token（纯字符串，用于调试）
SET SYSTEM_ACCESS_TOKEN "<上面生成的 ADMIN_TOKEN>"
```

**验证**：

```bash
GET SYSTEM_SM4_KEY          # 应返回 32 字符 hex 字符串
GET SYSTEM_APPKEY           # 应返回 JSON {"appKey":"...","appSecret":"..."}
GET sys_INTERFACE_VALID_TIME # 应返回 JSON {"systemValue":30}
GET SYSTEM_ACCESS_TOKEN     # 应返回非空字符串
```

> **注意**：Redis Key 名称大小写敏感。`SYSTEM_APPKEY` 中的 `KEY` 是大写，`sys_INTERFACE_VALID_TIME` 中的 `sys` 是小写。

### 2.3 WVP 配置文件添加 sy.enable

根据运行环境选择对应的配置文件：

#### 本地开发环境（profile: 274-dev）

编辑 `src/main/resources/application-dev.yml`，在文件末尾添加：

```yaml
sy:
  enable: true
```

#### Docker 部署环境（profile: docker）

编辑 `docker/wvp/wvp/application-docker.yml`，在文件末尾添加：

```yaml
sy:
  enable: true
```

#### 自定义 profile 环境

在对应的 `application-{profile}.yml` 中添加同样的内容。

### 2.4 security-management 配置

编辑 `security-management/config/settings.yml`，填入与 Redis 相同的密钥：

```yaml
# WVP SM3+SM4 signing configuration
wvp:
  appKey: "<上面生成的 APP_KEY>"
  appSecret: "<上面生成的 APP_SECRET>"
  sm4Key: "<上面生成的 SM4_KEY>"
  expiresMin: 30
```

**对应关系**：

| settings.yml 字段 | Redis Key | 说明 |
|-------------------|-----------|------|
| `wvp.appKey` | `SYSTEM_APPKEY` → `.appKey` | 客户端标识 |
| `wvp.appSecret` | `SYSTEM_APPKEY` → `.appSecret` | SM3 签名密钥 |
| `wvp.sm4Key` | `SYSTEM_SM4_KEY` | SM4-ECB 加密密钥（32 字符 hex） |
| `wvp.expiresMin` | `sys_INTERFACE_VALID_TIME` → `.systemValue` | 时间戳+token 过期时间（分钟） |

> **两端的值必须完全一致**，否则签名校验失败。

### 2.5 租户 WVP 平台配置

每个租户需要在 tenant-service 中配置 WVP 的 API 地址和 SIP realm。

通过前端界面（租户管理 → WVP 配置）或直接写入 ETCD：

```bash
# ETCD Key: jxt/tenants/{tenantID}/platform/wvp
etcdctl put "jxt/tenants/1/platform/wvp" '{"tenantId":1,"apiUrl":"http://polaris-wvp:18978","realm":"4101050000"}'
```

- `apiUrl`：WVP 的 HTTP API 地址（不带尾部斜杠）
- `realm`：WVP 的 SIP domain，必须与 WVP 配置中的 `sip.domain` 一致

## 3. 签名协议

### 3.1 请求格式

security-management 发送到 WVP 的每个请求都包含以下 query 参数：

```
POST {apiUrl}/api/sy/device?appKey={appKey}&accessToken={accessToken}&timestamp={timestamp}&sign={sign}
```

| 参数 | 说明 |
|------|------|
| `appKey` | 客户端标识 |
| `accessToken` | SM4-ECB 加密的 JSON token |
| `timestamp` | 请求时间戳（毫秒） |
| `sign` | SM3 签名 |

### 3.2 accessToken 生成

```
payload = {"expirationTime": <当前时间毫秒 + expiresMin * 60 * 1000>}
accessToken = HexEncode(SM4_ECB_Encrypt(sm4Key, payload))
```

- `sm4Key` 是 16 字节（32 字符 hex 解码得到）
- SM4 使用 ECB 模式
- 输出为 hex 编码字符串

### 3.3 sign 生成

```
1. 将所有 query 参数（除 sign 外）按 key 字母升序排列
2. 拼接: key1 + value1 + key2 + value2 + ...
3. 如果是 POST JSON 请求，追加 requestBody 原始字节
4. 追加 appSecret
5. sign = HexEncode(SM3(拼接结果))
```

**Go 端实现**（`security-management/common/wvp/sign_client.go`）：

```go
func (sc *SignClient) computeSign(params map[string]string, body []byte) string {
    keys := make([]string, 0, len(params))
    for k := range params {
        keys = append(keys, k)
    }
    sort.Strings(keys)

    var sb strings.Builder
    for _, k := range keys {
        sb.WriteString(k)
        sb.WriteString(params[k])
    }
    if len(body) > 0 {
        sb.Write(body)
    }
    sb.WriteString(sc.appSecret)

    hash := sm3.Sum([]byte(sb.String()))
    return hex.EncodeToString(hash[:])
}
```

**Java 端验证**（`SignAuthenticationFilter.java`）：

```java
// 参数排序
Set<String> paramKeys = new TreeSet<>(parameterMap.keySet());

StringBuilder beforeSign = new StringBuilder();
for (String paramKey : paramKeys) {
    if (paramKey.equals("sign")) continue;
    String[] values = parameterMap.get(paramKey);
    if (values != null && values.length > 0) {
        beforeSign.append(paramKey).append(values[0]);
    }
}
// POST JSON 追加 body
if (request.getContentLength() > 0 && "POST".equalsIgnoreCase(request.getMethod())
        && MediaType.APPLICATION_JSON_VALUE.equalsIgnoreCase(request.getContentType())) {
    beforeSign.append(request.getCachedBody());
}
beforeSign.append(secret);
String buildSign = SmUtil.sm3(beforeSign.toString());
```

两端算法完全一致：**字母排序参数 → key+value 拼接 → 追加 body → 追加 secret → SM3 哈希**。

### 3.4 WVP 校验流程

```
请求到达 /api/sy/*
    │
    ▼
SignAuthenticationFilter
    ├── 1. 检查必填参数 (sign/appKey/accessToken/timestamp)
    ├── 2. 从 SyTokenManager 查找 appKey → secret
    ├── 3. 计算 SM3 签名，比对 sign
    ├── 4. 检查 timestamp 是否过期（timestamp + expires*60*1000 > now）
    └── 5. 校验 accessToken
          ├── 如果 == adminToken → 直接放行
          └── 否则 SM4 解密 → 检查 expirationTime > now
```

## 4. 同步载荷格式

### 4.1 IAM 发送的 JSON

`POST /api/sy/device` 的请求体为 `IamSyncRequest`：

```json
{
  "schema_version": 1,
  "operation": "register",
  "target_deviceId": "41010500001320000001",
  "idempotency_key": "unique-key-xxx",
  "tenant_id": 1,
  "payload_specific": {
    "sipHa1": "a1b2c3d4e5f6a7b8a1b2c3d4e5f6a7b8",
    "realm": "4101050000",
    "deviceName": "执法仪-001",
    "charset": "GB2312",
    "streamMode": "TCP-PASSIVE",
    "sdpIp": "",
    "mediaServerId": "",
    "ssrcCheck": false,
    "geoCoordSys": "WGS84",
    "asMessageChannel": false,
    "broadcastPushAfterAck": true,
    "heartbeatInterval": 60,
    "heartbeatCount": 3
  }
}
```

| 字段 | 必填 | 说明 |
|------|------|------|
| `schema_version` | 是 | 必须为 `1` |
| `operation` | 是 | 必须为 `"register"` |
| `target_deviceId` | 是 | 20 位国标编码 |
| `idempotency_key` | 是 | 幂等键，WVP 用于去重 |
| `tenant_id` | 是 | 租户 ID |
| `payload_specific.sipHa1` | 是 | 32 字符 hex，MD5(deviceId:realm:password) |
| `payload_specific.realm` | 是 | SIP 域，必须与 WVP 的 `sip.domain` 一致 |

### 4.2 WVP 成功响应

```json
{
  "code": 0,
  "msg": "success"
}
```

### 4.3 WVP 错误响应

```json
{
  "code": 1,
  "msg": "参数非法"
}
```

| code | 含义 |
|------|------|
| 0 | 成功 |
| 1 | 参数非法（appKey 不存在 / 缺少必填参数） |
| 2 | 签名错误 |
| 3 | 接口已过期（timestamp 超时） |
| 4 | token 已过期或错误 |

## 5. 重试机制

security-management 的 `SyncDispatcher` 使用指数退避重试：

| 重试次数 | 等待时间 |
|----------|----------|
| 第 1 次 | 1 秒 |
| 第 2 次 | 5 秒 |
| 第 3 次 | 30 秒 |
| 第 4 次 | 5 分钟 |
| 第 5 次+ | 30 分钟（最大间隔） |

超过任务截止时间（Deadline）后进入 `dead_letter` 状态，不再重试。

## 6. 启动验证

### 6.1 启动顺序

```
1. Redis 写入 4 个凭证 Key（步骤 2.2）
2. ETCD 写入租户 WVP 配置（步骤 2.5）
3. 启动 WVP
4. 启动 security-management
```

### 6.2 检查 WVP 启动日志

WVP 启动成功时应出现：

```
[SY-读取Token] 成功
```

失败时会每 30 秒重试：

```
[SY-读取Token]失败，30秒后重试
[SY读取TOKEN] SYSTEM_ACCESS_TOKEN 读取失败
[SY读取TOKEN] SYSTEM_SM4_KEY 读取失败
[SY读取TOKEN] SYSTEM_APPKEY 读取失败
[SY读取TOKEN] sys_INTERFACE_VALID_TIME 读取失败
```

### 6.3 检查 security-management 启动日志

签名客户端启用成功：

```
WVP SignClient enabled with appKey: <your-app-key>
```

未配置时：

```
WVP SignClient disabled (no appKey/sm4Key configured)
```

### 6.4 端到端测试

1. 在 security-management 中创建一台 5G 执法仪
2. 观察 security-management 日志，SyncDispatcher 应推送任务到 WVP
3. 在 WVP 管理界面（设备管理）中应能看到新设备
4. 设备的 SIP 密码应已设置为 IAM 生成的 HA1 摘要

### 6.5 手动验证签名

使用 `curl` 手动发送一个测试请求：

```bash
# 需要自行实现 SM3+SM4 签名，或通过 security-management 的测试接口触发
# 以下仅为端点可达性测试（签名会失败，但能确认端点存在）

curl -v "http://<wvp-host>:<wvp-port>/api/sy/device?appKey=test&accessToken=test&timestamp=$(date +%s%3N)&sign=test" \
  -X POST \
  -H "Content-Type: application/json" \
  -d '{"schema_version":1,"operation":"register"}'

# 预期响应（签名错误，说明端点和过滤器已加载）：
# {"code":2,"msg":"签名错误"}
#
# 如果返回 404，说明 sy.enable 未生效
```

## 7. 注意事项

### 7.1 密钥安全

- `appSecret` 和 `sm4Key` 属于敏感凭据，不要提交到版本控制
- 生产环境建议通过环境变量注入：
  ```bash
  # security-management 支持的环境变量
  export WVP_APP_KEY="<appKey>"
  export WVP_APP_SECRET="<appSecret>"
  export WVP_SM4_KEY="<sm4Key>"
  ```
- Redis 凭据 Key 建议在部署脚本中写入，不要手工操作

### 7.2 多租户配置

- 每个租户通过 ETCD 配置独立的 WVP 地址（`apiUrl`）和 SIP 域（`realm`）
- SM3+SM4 签名密钥是全局共享的（所有租户使用同一套 `appKey`/`appSecret`/`sm4Key`）
- 不同租户的执法仪可以同步到不同的 WVP 实例

### 7.3 时钟同步

- 签名校验依赖 `timestamp`，security-management 和 WVP 的系统时钟必须同步
- 最大允许偏差由 `expiresMin` 控制（默认 30 分钟）
- 建议使用 NTP 保持时钟同步

### 7.4 回退机制

如果 `settings.yml` 中 `wvp.appKey` 或 `wvp.sm4Key` 为空，security-management 会注册 `NoopSigner`，请求不会附加签名参数。此时即使 WVP 启用了 `sy.enable`，签名校验也会因缺少参数而失败（返回 `code: 1, msg: 参数非法`）。

**因此两端必须同时配置**：WVP 启用 `sy.enable` + security-management 填写密钥。
