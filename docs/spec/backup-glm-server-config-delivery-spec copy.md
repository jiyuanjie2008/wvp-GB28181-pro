# 服务器配置下发（FTP / 图片服务器）- 需求规格与设计文档

> Date: 2026-04-29
> Status: Approved (HOLD SCOPE rev. v2)
> Reference: `gb28181_ftp_interface_analysis.md`（ZX 终端侧源码分析）
> Supersedes: `glm-ftp-config-delivery-spec.md`

---

## 1. 需求背景

### 1.1 系统上下文

本需求涉及三个系统的协作：

| 系统 | 职责 | 技术栈 |
|------|------|--------|
| **执法终端 (ZX)** | 现场录像采集设备，通过 GB28181 SIP 协议注册到 WVP 平台，收到服务器配置后自动上传录像/图片文件 | Android, C++ SIP 栈 |
| **WVP-GB28181-Pro** | GB28181 视频 surveillance 平台，负责 SIP 信令中转，管理终端设备的注册、心跳、媒体流 | Java 21, Spring Boot |
| **执法仪设备管理系统 (security-management)** | 执法仪设备的业务管理平台，管理设备信息、用户、FTP/图片服务器凭据等业务数据，是服务器配置的**数据来源**和**下发触发者** | Go, Gin |

### 1.2 触发流程

服务器配置下发的完整触发链路如下：

```
执法终端 (ZX)                 WVP-GB28181-Pro              执法仪设备管理系统
    │                              │                            │
    │── SIP REGISTER ─────────────▶│                            │
    │◀─ 200 OK ───────────────────│                            │
    │                              │                            │
    │                              │── 设备上线通知 (HTTP) ────▶│
    │                              │   (或 WVP 主动回调)         │
    │                              │                            │
    │                              │                            │  检索该设备对应的
    │                              │                            │  FTP / 图片服务器凭据
    │                              │                            │
    │                              │◀─ POST /api/v1/server-config ─│
    │                              │   {                        │
    │                              │     deviceId,              │
    │                              │     serverType,            │
    │                              │     config: {...}          │
    │                              │   }                        │
    │                              │                            │
    │◀─ SIP MESSAGE ──────────────│                            │
    │   (ServerCfgType/            │                            │
    │    ftpServerCfgType 或       │                            │
    │    pictureServerCfgType)     │                            │
    │                              │                            │
    │── SIP 200 OK ──────────────▶│                            │
    │                              │── HTTP 200 { code: 0 } ──▶│
    │                              │                            │
    │                              │                            │
    │  [终端开始自动上传文件到对应服务器]                             │
    │  ─ ─ ─ FTP/S3/HTTP ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─▶│
    │                              │                       文件服务器
```

**关键角色分工**：

- **执法仪设备管理系统**：拥有 FTP/图片服务器的凭据（IP、端口、用户名、密码），知道哪些设备需要下发哪种配置。它是本 HTTP 接口的**调用者**。
- **WVP-GB28181-Pro**：作为 SIP 信令中间层，将 HTTP 请求翻译为 GB28181 SIP MESSAGE 下发给终端。它是本 HTTP 接口的**提供者**。
- **执法终端 (ZX)**：配置的**接收者**，收到后存储凭据并启动对应的上传管道。

**触发时机**：执法仪设备管理系统在感知到终端设备上线后（通过 WVP 的设备上线通知机制），主动调用 WVP 的 HTTP 接口下发配置。终端侧不会主动请求配置，完全由平台侧驱动。

### 1.3 需求概述

在 WVP 后端实现一个新的 HTTP 接口，当收到 "服务器配置下发" 请求时，根据 `serverType` 字段通过 SIP MESSAGE 下发 FTP 或图片服务器配置给终端，并等待终端的 SIP `200 OK` 确认后返回 HTTP 响应。

平台需要向终端下发以下两类服务器配置：

- **FTP 服务器**：用于录像文件自动上传（`ftpServerCfgType`）
- **图片服务器**：用于抓拍图片上传（`pictureServerCfgType`，与 FTP 同族私有协议）

终端收到后仅回复 SIP 层 `200 OK`，**不发送应用层 XML 响应**。

**协议性质**：私有协议扩展，**不在 GB/T 28181 国标范围内**。`CmdType=ServerCfgType` + `ServerType=ftpServerCfgType|pictureServerCfgType` + `<FtpServerCfgType>` / `<PictureServerCfgType>` XML 载荷。

## 2. 功能需求

### 2.1 HTTP 接口

提供 **统一端点**，根据 `serverType` 字段分发到 FTP 或图片服务器配置下发。

**请求**：

- **Method**：`POST`
- **Path**：`/api/v1/server-config`
- **Content-Type**：`application/json`
- **Auth**：WVP 内置 API Key 机制（`api-key` 请求头）；详见 §9.1
- **可选请求头**：`X-Idempotency-Key`（UUID，见 §2.4）

**认证说明**：

本接口为服务间（M2M）调用，不使用 WVP 面向前端用户的 JWT 会话认证，而使用 WVP 内置的 **API Key** 机制。

| 对比项 | JWT（前端用户） | API Key（服务间） |
|--------|----------------|------------------|
| 请求头 | `access-token` | `api-key` |
| 生命周期 | 30 分钟过期，需刷新 | 可设永不过期 |
| 密钥依赖 | RSA 密钥对（jwk.json） | 无需共享密钥 |
| 用户体系 | 绑定 WVP 用户表 | 绑定 WVP 用户表 |
| 适用场景 | 浏览器 → WVP | 后端服务 → WVP |

**API Key 申请方式**：由 WVP 管理员通过 `POST /api/userApiKey/add` 为执法仪设备管理系统创建专用 API Key（绑定专用用户），可设置过期时间，支持随时禁用/重置。设备管理系统在每次 HTTP 请求中携带 `api-key` header 即可。

**请求示例**：

```http
POST /api/v1/server-config HTTP/1.1
Host: wvp:18978
Content-Type: application/json
api-key: <WVP管理后台生成的API Key>

{"deviceId": "...", "serverType": "ftp", "config": {...}}
```

**请求体（公共字段）**：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| deviceId | String | 是 | GB28181 设备编码（20 位数字） |
| serverType | String | 是 | 枚举：`ftp` \| `picture` |
| config | Object | 是 | 服务器配置对象，结构由 `serverType` 决定 |

#### 2.1.1 `serverType=ftp` 时 `config` 字段

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| ipv4Address | String | 是 | FTP 服务器 IPv4 地址 |
| ftpPort | int | 是 | FTP 服务器端口（1–65535） |
| userId | String | 是 | FTP 登录用户名 |
| userPasswd | String | 是 | FTP 登录密码 |

**请求示例**：

```json
{
  "deviceId": "35020000201311000070",
  "serverType": "ftp",
  "config": {
    "ipv4Address": "192.168.1.100",
    "ftpPort": 52488,
    "userId": "admin",
    "userPasswd": "dcw@ivs-100!"
  }
}
```

#### 2.1.2 `serverType=picture` 时 `config` 字段

> **注意**：本节字段以终端 `pictureServerCfgType` 解析代码为准，落地前须与终端开发对齐。当前按 FTP 对称推测，待确认。

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| ipv4Address | String | 是 | 图片服务器 IPv4 地址 |
| port | int | 是 | 图片服务器端口（1–65535） |
| userId | String | 是 | 登录用户名 |
| userPasswd | String | 是 | 登录密码 |
| protocol | String | 否 | 协议类型，预留 `ftp`/`http`/`s3` 等枚举（默认由终端决定） |

**请求示例**：

```json
{
  "deviceId": "35020000201311000070",
  "serverType": "picture",
  "config": {
    "ipv4Address": "192.168.1.101",
    "port": 8080,
    "userId": "admin",
    "userPasswd": "dcw@ivs-100!"
  }
}
```

### 2.2 SIP MESSAGE 下发

WVP 收到 HTTP 请求后，构造以下 SIP MESSAGE 发送给终端：

- **SIP Method**：MESSAGE
- **Content-Type**：`Application/MANSCDP+xml`

#### 2.2.1 FTP 配置 XML

```xml
<?xml version="1.0"?>
<Control>
  <CmdType>ServerCfgType</CmdType>
  <SN>{runtime-generated}</SN>
  <DeviceID>35020000201311000070</DeviceID>
  <ServerType>ftpServerCfgType</ServerType>
  <Version>1.0</Version>
  <FtpServerCfgType>
    <Ipv4Address>192.168.1.100</Ipv4Address>
    <FTPPort>52488</FTPPort>
    <UserId>admin</UserId>
    <UserPasswd>dcw@ivs-100!</UserPasswd>
  </FtpServerCfgType>
</Control>
```

XML 节点与终端解析代码完全对齐：

| XML 节点 | 终端写入目标 |
|----------|------------|
| `FtpServerCfgType/Ipv4Address` | `FTPService.setFtpserviceIp()` |
| `FtpServerCfgType/FTPPort` | `FTPService.setFtpservicePort()` |
| `FtpServerCfgType/UserId` | `FTPService.setFtpserviceName()` |
| `FtpServerCfgType/UserPasswd` | `FTPService.setFTPServicePWD()` |

#### 2.2.2 图片服务器配置 XML

```xml
<?xml version="1.0"?>
<Control>
  <CmdType>ServerCfgType</CmdType>
  <SN>{runtime-generated}</SN>
  <DeviceID>35020000201311000070</DeviceID>
  <ServerType>pictureServerCfgType</ServerType>
  <Version>1.0</Version>
  <PictureServerCfgType>
    <Ipv4Address>192.168.1.101</Ipv4Address>
    <Port>8080</Port>
    <UserId>admin</UserId>
    <UserPasswd>dcw@ivs-100!</UserPasswd>
  </PictureServerCfgType>
</Control>
```

> 字段名以终端解析为准，待对齐。

### 2.3 终端响应处理

终端收到 `ServerCfgType` 后：

1. C++ SIP 栈自动回复 SIP `200 OK`（RFC 3261 标准行为）
2. Java 层解析 XML，写入对应 KV 存储（`FTPService` / 图片服务对应类）
3. FTP 路径下设置 `FTPService.setIsReceive(true)`，唤醒自动上传生产者线程
4. **不发送任何应用层 GB28181 XML 响应**

WVP 侧只需等待 SIP 层面的 `200 OK`，无需处理应用层响应。

### 2.4 幂等性

**请求头** `X-Idempotency-Key`（UUID，调用方生成）为可选幂等键。平台缓存 `key → (response, SN, callId, createdAt)` **24 小时**。

| 重复请求场景 | 行为 |
|--------------|------|
| 原请求仍在处理中 | 返回 `code=409, msg=请求处理中`，**不重新下发 SIP MESSAGE** |
| 原请求已完成（24h 内） | 返回**与首次完全一致的响应**（含原 `code`、`msg`、`SN`、`callId`），**不重新下发 SIP MESSAGE** |
| 缓存过期（>24h）后相同 key | 视为新请求，重新下发 |
| 未携带 `X-Idempotency-Key` | 不启用幂等，每次按新请求处理 |
| 同 key 但请求体不同 | 返回 `code=422, msg=幂等键已绑定不同请求体` |

**契约约定**：

- 调用方应在 24 小时内使用同一 key 重试同一逻辑请求；超过该窗口意味着这是**业务上的新一次操作**
- 命中缓存时响应中的 `SN` / `callId` 必须与首次一致，便于调用方与 SIP 抓包对账
- 平台对请求体计算 SHA-256 摘要存入缓存，用于 "同 key 不同 body" 的检测，防止误用

**实现**：Redis `SETNX` + TTL=86400s，key 为 `wvp:server-config:idempotency:{key}`，value 为 `{requestHash, responseSnapshot, sn, callId, createdAt}` 的 JSON。

## 3. 响应规格

### 3.1 成功响应

当终端回复 SIP `200 OK` 后：

```json
{
  "code": 0,
  "msg": "成功",
  "data": null
}
```

### 3.2 错误响应

| 场景 | HTTP | code | msg |
|------|:----:|:----:|-----|
| 参数缺失 / 类型错误 | 200 | 400 | 参数错误: {field} {reason} |
| `serverType` 非法 | 200 | 400 | serverType 必须为 ftp 或 picture |
| IP 格式非法 | 200 | 400 | ipv4Address 格式非法 |
| 端口越界 | 200 | 400 | port 必须在 1–65535 |
| API Key 无效 / 缺失 | 401 | – | Spring Security 默认响应 |
| 设备不存在 | 200 | 404 | 设备不存在 |
| 设备离线 | 200 | 404 | 设备离线 |
| 幂等键冲突（处理中） | 200 | 409 | 请求处理中 |
| 设备锁占用 | 200 | 423 | 设备配置下发中 |
| 调用方超频 | 200 | 429 | 请求过于频繁 |
| SIP 栈异常 | 200 | 500 | 发送失败: {reason} |
| 终端未应答（5s） | 200 | 486 | 设备未应答 |
| 内部未知异常 | 200 | 500 | 服务内部错误 |

**HTTP 状态码约定**：除认证失败用标准 `401` 外，其余均返回 HTTP `200`，业务状态码在 JSON body `code` 字段中（与 WVP 既有风格一致）。

## 4. 技术设计

### 4.1 设计方案：SipSubscribe 模式

终端仅回复 SIP 层 `200 OK`，不回复应用层 XML，故使用 WVP 现有 **`SipSubscribe`** 模式（与 PTZ 控制命令 `fronEndCmd` 一致），而非 `MessageSubscribe`。

#### 为什么不用 MessageSubscribe？

WVP 中存在两种 SIP 响应订阅机制，适用场景截然不同：

| 机制 | 触发条件 | 适用场景 | 典型命令 |
|------|---------|---------|---------|
| **SipSubscribe** | 终端回复 SIP 层 `200 OK`（RFC 3261 标准行为） | 终端仅做 SIP 层确认，不返回应用层 XML 响应 | PTZ 控制、设备重启、**本需求** |
| **MessageSubscribe** | 终端回复 SIP MESSAGE 携带应用层 XML（如 `<Response>` 或 `<Notify>`） | 终端需要返回业务数据（状态、查询结果等） | 设备状态查询、目录查询、录像查询 |

**不使用 MessageSubscribe 的原因**：

1. **终端不会发送应用层 XML 响应**：根据终端侧源码分析（`gb28181_ftp_interface_analysis.md`），终端收到 `ServerCfgType` 后，仅由 C++ SIP 协议栈自动回复 SIP `200 OK`，Java 业务层直接解析 XML 并写入 `FTPService` KV 存储，**不会构造任何 GB28181 XML 响应消息**。
2. **MessageSubscribe 会永久等待**：`MessageSubscribe` 以 `callId` 为 key 等待终端发送一条新的 SIP MESSAGE 作为响应。如果终端不发送，该订阅将一直挂在内存中直到超时清理，造成资源浪费。
3. **SipSubscribe 已满足需求**：终端的 SIP `200 OK` 已经确认了 MESSAGE 送达，本需求只需知道"配置是否送达终端"，无需"终端是否成功应用配置"（后者在当前协议下无法获取）。

#### SipSubscribe 工作原理

- `sipSender.transmitRequest()` 发送 SIP MESSAGE 时，可注册 `okEvent` 与 `errorEvent` 回调
- `SipSubscribe` 以 `callId + cSeq` 为 key 存储回调
- 终端回复 SIP `200 OK` 时，WVP SIP 栈自动匹配并触发 `okEvent`
- 超时未收到则不触发（由 `DeferredResult` 的 `onTimeout` 兜底）

### 4.2 数据流

```
HTTP POST /api/v1/server-config
  │
  ▼
ServerConfigController（新增）
  │  参数校验 / 幂等键查重
  │  获取 server-config:lock:{deviceId}
  │  创建 DeferredResult<WVPResult<String>>
  │
  ▼
ServerConfigDeliverService.deliver()（新增统一服务）
  │  根据 serverType 分发
  │
  ▼
SIPCommander.serverConfigCmd(device, serverType, config, ...)（新增）
  │  生成 SN
  │  构造 ServerCfgType XML（FTP / Picture 分支）
  │  createMessageRequest(device, xml)
  │  transmitRequest(request, errorEvent, okEvent)
  │
  ▼
[SIP MESSAGE ──────────────────▶ 终端]
  │                                │
  │  [SIP 200 OK ◀─────────────── │]
  │                                │
  ▼
okEvent / errorEvent / onTimeout
  │  释放锁、写幂等缓存、setResult
  │
  ▼
HTTP { "code": 0, "msg": "成功" }
```

### 4.3 超时与回调竞态

| 到达顺序 | 处理 |
|----------|------|
| `okEvent` 先于 5s 超时 | `deferredResult.setResult(success)`；`onTimeout` 被 Spring 抑制；释放锁 |
| `errorEvent` 先于 5s 超时 | `deferredResult.setResult(fail(500, reason))`；释放锁 |
| 5s 超时先于任何回调 | `onTimeout` 设置 `fail(486)`，**主动调用** `sipSubscribe.removeOkSubscribe(key)` + `removeErrorSubscribe(key)` 防内存泄漏；释放锁；之后到达的 `okEvent` **必须用 `deferredResult.hasResult()` 守卫**，禁止再次 `setResult` |
| `okEvent` 与 `errorEvent` 同时到达（SIP 栈重传） | `SipSubscribe` 保证每个 `callId+cSeq` 只回调一次，以先到为准 |

**强制约定**：`onTimeout` 必须显式反注册 `SipSubscribe`，否则长期运行将累积订阅条目造成内存泄漏。

### 4.4 SN 生成策略

- SN 为 32 位正整数，范围 `[1, Integer.MAX_VALUE)`
- 平台级 `AtomicInteger`，CAS 自增，溢出后回绕到 1
- 启动时从 Redis `wvp:sip:sn:counter` 恢复；每 1000 次自增后异步持久化一次
- XML 中 `<SN>` 节点取此运行期值，禁止硬编码

### 4.5 同设备并发控制

Redis 分布式锁：

- key：`wvp:server-config:lock:{deviceId}`
- value：本次请求生成的 UUID（用于释放时防误删）
- TTL：**8 秒**（> DeferredResult 5s 超时 + 3s SIP 往返余量）

**FTP 与图片服务器共用同一把设备级锁**，理由：

1. 终端 Java 层处理 `ServerCfgType` 的解析与 KV 写入路径**不保证线程安全**；并发到达的两个 `ServerCfgType` MESSAGE 在终端侧可能交叉写入，导致配置错乱
2. 单条 SIP MESSAGE 处理时延 < 1s，串行化对吞吐影响可忽略
3. 简化心智模型："任一时刻一台设备最多只有一次配置下发在飞" 比 "按 serverType 维度并行" 更易排错

如未来证实终端侧已支持并发安全解析，可升级为按 `serverType` 拆分的细粒度锁 `wvp:server-config:lock:{deviceId}:{serverType}`，本规格不在本期实施。

**行为**：

- 获取锁失败 → 立即返回 `code=423, msg=设备配置下发中，请稍后重试`
- 锁必须在 `okEvent` / `errorEvent` / `onTimeout` 三个路径任一触发时**主动释放**（`finally` 语义），不依赖 TTL 自然过期
- 释放时使用 Lua 脚本 `if redis.call('GET', KEYS[1]) == ARGV[1] then redis.call('DEL', KEYS[1]) end` 校验 value，防止超时后误删后续请求的锁

## 5. 代码变更清单

| 文件 | 操作 | 说明 |
|------|------|------|
| `gb28181/transmit/cmd/impl/SIPCommander.java` | 新增方法 | `serverConfigCmd(device, serverType, config, okEvent, errorEvent)` — 构造 XML + 发送 SIP MESSAGE |
| `gb28181/controller/ServerConfigController.java` | 新建 | `POST /api/v1/server-config` 端点 |
| `gb28181/controller/bean/ServerConfigVO.java` | 新建 | 请求 VO，含校验注解 |
| `gb28181/controller/bean/FtpConfig.java` | 新建 | FTP 子配置 VO |
| `gb28181/controller/bean/PictureServerConfig.java` | 新建 | 图片服务器子配置 VO |
| `service/IServerConfigService.java` | 新建 | 统一下发服务接口 |
| `service/impl/ServerConfigServiceImpl.java` | 新建 | 实现：幂等、分布式锁、回调编排、metrics |
| `service/redisMsg/RedisIdempotencyService.java` | 新建（或复用） | 幂等键缓存 |
| `service/redisMsg/RedisDeviceLockService.java` | 新建（或复用） | 设备级分布式锁，带 Lua 释放 |
| `gb28181/utils/IPv4Validator.java` | 新建 | `@IPv4` 自定义校验注解 + ConstraintValidator |

**无需新增数据库表。** 审计仅依赖结构化日志（见 §10）。

### 5.1 SIPCommander.serverConfigCmd

```java
public void serverConfigCmd(Device device,
    ServerType serverType,             // 枚举: FTP / PICTURE
    ServerConfigPayload config,
    SipSubscribe.Event okEvent,
    SipSubscribe.Event errorEvent)
    throws InvalidArgumentException, SipException, ParseException
```

- 内部根据 `serverType` 分支生成 `<FtpServerCfgType>` 或 `<PictureServerCfgType>`
- 使用 `headerProvider.createMessageRequest(device, xml, ...)` 创建请求
- 使用 `sipSender.transmitRequest(ip, request, errorEvent, okEvent, null)` 发送

### 5.2 Controller 端点

```java
@PostMapping("/api/v1/server-config")
@Operation(summary = "下发服务器配置（FTP/图片服务器）", security = @SecurityRequirement(name = JwtUtils.API_KEY_HEADER))
public DeferredResult<WVPResult<String>> deliver(@RequestBody @Valid ServerConfigVO vo)
```

- 使用 `DeferredResult<WVPResult<String>>` 异步等待
- 5 秒超时兜底（`onTimeout` 释放锁 + 反注册 SipSubscribe）

### 5.3 请求 VO 校验

`ServerConfigVO` 公共字段：

| 字段 | 校验 |
|------|------|
| deviceId | `@NotBlank @Pattern("^\\d{20}$")` |
| serverType | `@NotNull` 枚举 `FTP` \| `PICTURE` |
| config | `@NotNull @Valid` |

`FtpConfig` / `PictureServerConfig`：

| 字段 | 校验 |
|------|------|
| ipv4Address | `@NotBlank @IPv4`（拒绝 `0.0.0.0`、`255.255.255.255`、环回段；可配置白名单开关） |
| ftpPort / port | `@Min(1) @Max(65535)`；< 1024 时记录 warn 日志 |
| userId | `@NotBlank @Size(max=64) @Pattern("^[A-Za-z0-9_\\-.@]+$")` |
| userPasswd | `@NotBlank @Size(min=1, max=128)`（不强制复杂度，由服务器侧定） |

## 6. 信令交互时序

```
WVP 平台                        终端（ZX）
    │                               │
    │─── SIP MESSAGE ─────────────▶│
    │    Content-Type: Application/MANSCDP+xml
    │    <Control>
    │      <CmdType>ServerCfgType</CmdType>
    │      <SN>...</SN>
    │      <DeviceID>...</DeviceID>
    │      <ServerType>ftpServerCfgType | pictureServerCfgType</ServerType>
    │      <Version>1.0</Version>
    │      <FtpServerCfgType>...</FtpServerCfgType>      ※ 二选一
    │      <PictureServerCfgType>...</PictureServerCfgType>
    │    </Control>                 │
    │                               │ ├── 解析 XML
    │                               │ ├── 写入对应 KV 服务
    │                               │ └── (FTP) setIsReceive(true) 唤醒上传线程
    │                               │
    │◀── SIP 200 OK ───────────────│ (C++ SIP 栈自动回复)
    │                               │
    │ [HTTP 响应: code=0, 成功]      │
    │                               │
    │                               │ [FtpProducer / 图片上传线程被唤醒]
    │    ※ 实际上传不经过 GB28181    │
```

## 7. 非功能性需求

| 项目 | 要求 |
|------|------|
| 接口认证 | WVP 内置 API Key 机制（`api-key` 请求头），由 WVP 管理员为设备管理系统创建专用 Key（详见 §2.1、§9.1） |
| 传输安全 | 按部署拓扑区分：同机 HTTP、跨机 HTTPS（详见 §9.1） |
| 同设备并发 | Redis 分布式锁，详见 §4.5 |
| 超时 | DeferredResult 5s；锁 TTL 8s；幂等缓存 24 小时 |
| 日志 | 结构化日志，详见 §10 |
| 向后兼容 | 不影响现有 SIP 命令处理流程；新增 `<Version>` 节点终端可忽略 |
| 传输协议 | 与设备注册时使用的传输协议一致（TCP/UDP） |

## 8. 限制与约束

1. **私有协议**：`ServerCfgType` / `ftpServerCfgType` / `pictureServerCfgType` 是 ZX 终端的私有扩展，非 GB/T 28181 标准。仅适用于已对接此私有协议的终端设备。
2. **单向确认**：只能确认 SIP MESSAGE 是否到达终端（SIP 200 OK），无法确认终端是否成功解析和应用了配置。后续如需端到端确认，需终端补充上报机制。
3. **图片服务器字段未冻结**：§2.1.2 / §2.2.2 字段以终端 `pictureServerCfgType` 解析代码为准，本规格按对称推测，**实现前需与终端开发对齐并更新本节**。
4. **SIP 明文**：SIP MESSAGE 中密码以明文传输，受限于终端协议，无法在传输层加密。详见 §9。

## 9. 安全要求

### 9.1 传输层与认证

#### 9.1.1 认证方式

使用 WVP 内置 API Key 机制（非前端 JWT）。

- 请求头：`api-key: <key>`，WVP 的 `JwtAuthenticationFilter` 自动识别并走 `verifyToken()` 验证流程
- API Key 由 WVP 管理员通过 `POST /api/userApiKey/add` 创建，绑定 WVP 中已有的专用用户（如 `svc-security-mgmt`）
- API Key 可设置过期时间或永不过期，支持随时禁用/重置
- API Key 本质是一个 RS256 JWT token，包含 `userName` 和 `apiKeyId` 两个 claims，WVP 验证时会检查关联用户是否存在且 API Key 是否启用

**为什么不使用 JWT 用户会话认证**：

1. JWT `access-token` 是为前端用户会话设计的，30 分钟过期需要刷新机制，不适合服务间长期调用
2. JWT 签名验证依赖 RSA 密钥对（`jwk.json`），若让设备管理系统自己签发 token 则需要共享私钥，增加密钥泄露风险
3. API Key 由 WVP 自己签发和验证，设备管理系统只需持有 Key 字符串，无需接触 RSA 密钥

#### 9.1.2 传输安全（按部署拓扑区分）

| 部署拓扑 | WVP ↔ 设备管理系统 | 理由 |
|----------|-------------------|------|
| **同机 Docker bridge** | **HTTP** | 流量不经过物理网卡（内核内存拷贝），外部无法嗅探，HTTPS 无安全收益 |
| **跨机 Docker overlay** | **HTTPS** | 流量经物理网线/交换机，可被同网段主机抓包，请求体含 FTP 明文密码必须加密 |
| **公网 / 不受控网络** | **HTTPS**（必须） | 必须加密 + HMAC 签名防篡改 |

**同机 Docker bridge（HTTP）**：

```
┌─────────────────────── 宿主机 ───────────────────────┐
│  Docker bridge: media-net                             │
│  ┌──────────────┐    HTTP (内核内存)    ┌───────────┐ │
│  │ polaris-wvp  │◀────────────────────▶│ security  │ │
│  │ :18978       │                      │ :8000      │ │
│  └──────────────┘                      └───────────┘ │
│  流量不离开宿主机，无需 HTTPS                          │
└──────────────────────────────────────────────────────┘
```

**跨机 Docker overlay（HTTPS）**：

```
┌────── 物理机 A ──────┐        ┌────── 物理机 B ──────┐
│  Docker overlay       │  物理   │  Docker overlay       │
│  ┌──────────────┐    │  网络   │  ┌──────────────┐    │
│  │ polaris-wvp  │◀───┼────────┼──▶│ security     │    │
│  │ :8443 HTTPS  │    │  交换机  │  │ :8000        │    │
│  └──────────────┘    │        │  └──────────────┘    │
└──────────────────────┘        └──────────────────────┘
   流量经物理网线 → 必须加密（自签证书 + 内网 CA）
```

Docker overlay 网络默认**只加密控制面**（ETCD/Consul 通信），数据面 HTTP 仍为明文，不要误以为 overlay = 加密。

#### 9.1.3 跨机 HTTPS 实施方案

**方案：自签内网 CA + 服务端证书**

运维一次性工作：

```bash
# 1. 生成内网 CA（整个环境一次）
openssl genrsa -out ca.key 4096
openssl req -x509 -new -nodes -key ca.key -sha256 -days 3650 -out ca.crt \
  -subj "/CN=JXT Internal CA"

# 2. 为 WVP 签发服务端证书
openssl req -new -key wvp.key -out wvp.csr \
  -subj "/CN=polaris-wvp" -addext "subjectAltName=DNS:polaris-wvp,IP:10.0.0.1"
openssl x509 -req -in wvp.csr -CA ca.crt -CAkey ca.key -CAcreateserial \
  -days 365 -out wvp.crt -copy_extensions copyall

# 3. 转为 PKCS12（Spring Boot 使用）
openssl pkcs12 -export -in wvp.crt -inkey wvp.key -out keystore.p12 -name wvp

# 4. ca.crt 分发给设备管理系统（Go 侧信任该 CA）
```

WVP Spring Boot 配置：

```yaml
server:
  ssl:
    enabled: true
    key-store: classpath:keystore.p12
    key-store-password: ${SSL_KEYSTORE_PASSWORD}
    key-store-type: PKCS12
  port: 8443
```

设备管理系统 Go 侧：

```go
certPool := x509.NewCertPool()
certPool.AppendCertsFromPEM(caCertBytes)  // 信任内网自签 CA
client := &http.Client{
    Transport: &http.Transport{TLSClientConfig: &tls.Config{RootCAs: certPool}},
}
```

#### 9.1.4 SIP 传输安全

SIP MESSAGE 明文不可避免（终端协议限制），文档与运维须知中明示该风险，建议部署在受控内网。

#### 9.1.5 运行时强制开关

配置项 `wvp.server-config.require-https`（默认 `false`）控制代码层兜底：

| 取值 | 行为 |
|------|------|
| `false`（默认） | 允许 HTTP 调用；每次 HTTP 调用打 WARN 日志 `[SECURITY] server-config called over plaintext HTTP from {clientIp}, ensure topology is same-host` |
| `true` | 拒绝 HTTP 调用，返回 `code=400, msg=server-config 接口要求 HTTPS`；HTTPS 调用正常通过 |

部署建议：

- 同机 Docker bridge 部署：保持默认 `false`
- 跨机 / 公网部署：显式设置 `true`，与 §9.1.2 拓扑选择形成代码闭环
- **不**在启动期 fail-fast 拒绝注册端点，以保证开发/单测环境（无 TLS）可正常使用

#### 9.1.6 未来升级路径

若未来需要更高安全性（如 API 调用经过公网），可升级为 HMAC 请求签名：
- 签名算法：HMAC-SHA256
- 签名内容：`METHOD + "\n" + PATH + "\n" + TIMESTAMP + "\n" + SHA256(BODY)`
- 请求头：`X-App-Id`、`X-Timestamp`、`X-Signature`
- 每个请求有独立签名，防重放攻击，密钥不在网络上传输

### 9.2 日志层

- **任何日志中均不输出 `userPasswd` 原文**
- 统一脱敏格式：`userPasswd=***(len=N)`，N 为原长度
- 异常栈打印时若需脱敏字段值，使用 `MaskedString` 包装类

### 9.3 存储层

- 幂等缓存中的响应快照**不得包含密码原文**
- 若未来引入持久化（DB 表 / 文件），密码字段必须 AES-GCM 加密；密钥来源于 `application.yml` 的 `user.settings.server-key`（WVP 既有机制）
- 明文密码仅在内存中短暂存在（VO 反序列化 → SIPCommander 构造 XML 即释放引用）

### 9.4 代码约定

- `FtpConfig.userPasswd` / `PictureServerConfig.userPasswd` 字段加 `@JsonProperty(access = JsonProperty.Access.WRITE_ONLY)`，防止误回显
- 两个 VO 类覆盖 `toString()`，密码字段固定输出 `***(len=N)`
- Lombok `@ToString` 不可用于含密码的类（或用 `@ToString.Exclude` 标注密码字段）

## 10. 日志规范

不建立独立审计 DB 表，仅依赖结构化日志（运维 ELK/Loki 可检索）。

### 10.1 日志事件

每次下发产生 3 条日志，统一以 `server_config_` 前缀便于 grep：

| 事件 | 触发时机 | 级别 |
|------|---------|------|
| `server_config_start` | 控制器入口、参数校验通过、获取锁后 | INFO |
| `server_config_sent` | SIP MESSAGE 已通过 `sipSender.transmitRequest()` 发出 | INFO |
| `server_config_done` | `okEvent` / `errorEvent` / `onTimeout` 任一触发，写入最终结果 | INFO（成功/超时）/ WARN（失败） |

### 10.2 字段约定

所有事件均包含以下结构化字段（推荐使用 SLF4J + KV 风格，或 `Markers`）：

```
event=server_config_start
device=35020000201311000070
server_type=ftp
sn=12345
call_id=<sip-call-id>
idempotency_key=<uuid-or-empty>
api_key_id=<api-key-id>
caller=security-management
client_ip=192.168.0.5
ipv4_address=192.168.1.100
port=52488
user_id=admin
user_passwd=***(len=12)
```

`server_config_done` 额外包含：

```
result_code=0
result_msg=成功
duration_ms=143
```

### 10.3 实现要求

- 日志写入**不得阻塞下发流程**：使用 Logback `AsyncAppender` 或 Log4j2 异步 logger
- 兜底策略：异步队列满时丢弃**调试级日志**，但 `server_config_*` 级别为 INFO/WARN，不允许丢弃；若 appender 不可用则降级到本地 `logs/server-config.fallback.log`

## 11. 可观测性

### 11.1 Metrics（Micrometer）

| 名称 | 类型 | 标签 |
|------|------|------|
| `wvp_server_config_total` | Counter | `server_type`（ftp/picture）, `result`（success/fail）, `code` |
| `wvp_server_config_duration_seconds` | Histogram | `server_type`, `result`, `code` |
| `wvp_server_config_inflight` | Gauge | `server_type` — 当前持锁中的请求数 |

### 11.2 Tracing

- Controller 入口创建 span `server.config.deliver`
- 标签：`device.id`、`server.type`、`sn`、`call.id`、`idempotency.key`
- 子 span：`sip.send`、`sip.wait_ok`

### 11.3 日志

详见 §10。

## 12. 版本与兼容性

### 12.1 协议版本

XML 载荷 `<Control>` 下新增可选节点 `<Version>1.0</Version>`：

- 终端当前实现忽略未知节点 → 添加安全
- 后续若 `pictureServerCfgType` 字段调整或新增 `serverType` 枚举值，可通过 `<Version>` 差异化行为
- 终端如需识别版本，由终端侧后续升级支持；本期版本号仅为预留位

### 12.2 HTTP 接口版本

- 接口路径：`POST /api/v1/server-config`（首发版本，无旧版兼容负担）
- 路径中的 `/v1` 为版本号预留位，后续若有破坏性变更（如字段结构、响应格式调整），通过递增版本号（`/v2`）实现平滑迁移，`/v1` 保留至少两个版本周期
