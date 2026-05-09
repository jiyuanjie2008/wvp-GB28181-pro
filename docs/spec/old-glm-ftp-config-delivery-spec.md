# FTP 服务器配置下发 - 需求规格与设计文档

> Date: 2026-04-28
> Status: Approved
> Reference: `gb28181_ftp_interface_analysis.md` (ZX 终端侧源码分析)

---

## 1. 需求背景

### 1.1 系统上下文

本需求涉及三个系统的协作：

| 系统 | 职责 | 技术栈 |
|------|------|--------|
| **执法终端 (ZX)** | 现场录像采集设备，通过 GB28181 SIP 协议注册到 WVP 平台，收到 FTP 配置后自动上传录像文件 | Android, C++ SIP 栈 |
| **WVP-GB28181-Pro** | GB28181 视频 surveillance 平台，负责 SIP 信令中转，管理终端设备的注册、心跳、媒体流 | Java 21, Spring Boot |
| **执法仪设备管理系统 (security-management)** | 执法仪设备的业务管理平台，管理设备信息、用户、FTP 服务器凭据等业务数据，是 FTP 配置的**数据来源**和**下发触发者** | Go, Gin |

### 1.2 触发流程

FTP 配置下发的完整触发链路如下：

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
    │                              │                            │  FTP 服务器凭据
    │                              │                            │
    │                              │◀─ HTTP POST /api/ftp/config ─│
    │                              │   {                        │
    │                              │     deviceId,              │
    │                              │     ipv4Address,           │
    │                              │     ftpPort,               │
    │                              │     userId,                │
    │                              │     userPasswd             │
    │                              │   }                        │
    │                              │                            │
    │◀─ SIP MESSAGE ──────────────│                            │
    │   (ServerCfgType/            │                            │
    │    ftpServerCfgType)         │                            │
    │                              │                            │
    │── SIP 200 OK ──────────────▶│                            │
    │                              │── HTTP 200 { code: 0 } ──▶│
    │                              │                            │
    │                              │                            │
    │  [终端开始自动上传录像文件到 FTP 服务器]                      │
    │  ─ ─ ─ FTP/SFTP ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ▶│
    │                              │                       FTP 服务器
```

**关键角色分工**：
- **执法仪设备管理系统**：拥有 FTP 服务器凭据（IP、端口、用户名、密码），知道哪些设备需要下发配置。它是本 HTTP 接口的**调用者**。
- **WVP-GB28181-Pro**：作为 SIP 信令中间层，将 HTTP 请求翻译为 GB28181 SIP MESSAGE 下发给终端。它是本 HTTP 接口的**提供者**。
- **执法终端 (ZX)**：配置的**接收者**，收到后存储凭据并启动自动上传管道。

### 1.3 需求概述

执法仪设备管理系统在感知到终端设备上线后（通过 WVP 的设备上线通知机制），调用 WVP 的 HTTP 接口，将 FTP 服务器配置下发给指定终端。

本需求在 WVP 后端实现一个新的 HTTP 接口，当收到 "FTP 服务器配置下发" 请求时，通过 SIP MESSAGE 下发 FTP 服务器配置给终端，并等待终端的 SIP `200 OK` 确认后返回 HTTP 响应。

这是一个 **私有协议扩展**，不在 GB/T 28181 国标范围内。终端使用自定义 `CmdType=ServerCfgType`、`ServerType=ftpServerCfgType` 及 `<FtpServerCfgType>` XML 载荷。终端收到后仅回复 SIP 层面的 `200 OK`，不发送应用层 XML 响应。

## 2. 功能需求

### 2.1 HTTP 接口

提供一个 RESTful HTTP 接口，接收 FTP 服务器配置参数，并通过 SIP MESSAGE 下发给指定终端设备。

**请求**：

- **Method**: `POST`
- **Path**: `/api/ftp/config`
- **Content-Type**: `application/json`

**请求体**：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| deviceId | String | 是 | 终端设备 ID（GB28181 设备编码） |
| ipv4Address | String | 是 | FTP 服务器 IPv4 地址 |
| ftpPort | int | 是 | FTP 服务器端口 |
| userId | String | 是 | FTP 登录用户名 |
| userPasswd | String | 是 | FTP 登录密码 |

**请求示例**：

```json
{
  "deviceId": "35020000201311000070",
  "ipv4Address": "192.168.1.100",
  "ftpPort": 52488,
  "userId": "admin",
  "userPasswd": "dcw@ivs-100!"
}
```

### 2.2 SIP MESSAGE 下发

WVP 收到 HTTP 请求后，构造以下 SIP MESSAGE 发送给终端：

- **SIP Method**: MESSAGE
- **Content-Type**: `Application/MANSCDP+xml`

**XML 载荷**：

```xml
<?xml version="1.0"?>
<Control>
  <CmdType>ServerCfgType</CmdType>
  <SN>12345</SN>
  <DeviceID>35020000201311000070</DeviceID>
  <ServerType>ftpServerCfgType</ServerType>
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

### 2.3 终端响应处理

终端收到 `ServerCfgType` 后：
1. C++ SIP 栈自动回复 SIP `200 OK`（RFC 3261 标准行为）
2. Java 层解析 XML，写入 FTPService KV 存储
3. 设置 `FTPService.setIsReceive(true)`，唤醒自动上传生产者线程
4. **不发送任何应用层 GB28181 XML 响应**

WVP 侧只需等待 SIP 层面的 `200 OK`，无需处理应用层响应。

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

| 场景 | code | msg | 说明 |
|------|------|-----|------|
| 设备不存在 | 100 | 设备不存在 | deviceId 查不到设备 |
| 设备离线 | 100 | 设备离线 | 设备未注册或已掉线 |
| SIP 发送失败 | 100 | 发送失败，{reason} | SIP 栈发送异常 |
| 终端未应答 | 486 | 设备未应答 | 等待 SIP 200 OK 超时（5 秒） |
| 参数校验失败 | 400 | 参数或方法错误 | 缺少必填字段或类型错误 |

**HTTP 状态码**：所有响应均为 `200 OK`，业务状态码在 JSON body 的 `code` 字段中。

## 4. 技术设计

### 4.1 设计方案：SipSubscribe 模式

终端仅回复 SIP 层 `200 OK`，不回复应用层 XML。因此使用 WVP 现有的 **`SipSubscribe`** 模式（与 PTZ 控制命令 `fronEndCmd` 相同），而非 `MessageSubscribe` 模式（后者需要应用层 XML 响应）。

**SipSubscribe 工作原理**：
- `sipSender.transmitRequest()` 发送 SIP MESSAGE 时，可注册 `okEvent` 和 `errorEvent` 回调
- `SipSubscribe` 以 `callId + cSeq` 为 key 存储回调
- 终端回复 SIP `200 OK` 时，WVP SIP 栈自动匹配并触发 `okEvent`
- 超时未收到则不触发（由 `DeferredResult` 的 `onTimeout` 兜底）

### 4.2 数据流

```
HTTP POST /api/ftp/config
  │
  ▼
DeviceQuery Controller (新增端点)
  │  验证参数，查找设备
  │  创建 DeferredResult<WVPResult<String>>
  │
  ▼
DeviceServiceImpl.ftpServerConfig() (新增方法)
  │  验证设备在线
  │
  ▼
SIPCommander.ftpServerConfigCmd() (新增方法)
  │  生成 SN
  │  构造 ServerCfgType XML
  │  createMessageRequest(device, xml)
  │  transmitRequest(request, errorEvent, okEvent)
  │
  ▼
[SIP MESSAGE ──────────────────▶ 终端]
  │                                │
  │  [SIP 200 OK ◀─────────────── │]
  │                                │
  ▼
okEvent 回调触发
  │
  ▼
DeferredResult.setResult(WVPResult.success())
  │
  ▼
HTTP 200 { "code": 0, "msg": "成功" }
```

### 4.3 超时机制

- `DeferredResult` 超时：5 秒（与设备状态查询等交互保持一致）
- 超时后返回 `WVPResult.fail(486, "设备未应答")`
- `SipSubscribe` 回调可能在超时后才到达，此时 `DeferredResult` 已设置，不会覆盖

## 5. 代码变更清单

| 文件 | 操作 | 说明 |
|------|------|------|
| `gb28181/transmit/cmd/impl/SIPCommander.java` | 新增方法 | `ftpServerConfigCmd()` — 构造 XML + 发送 SIP MESSAGE |
| `gb28181/controller/DeviceQuery.java` | 新增端点 | `POST /api/ftp/config` — 接收 HTTP 请求 |
| `service/IDeviceService.java` | 新增方法 | `ftpServerConfig(device, channelId, ipv4Address, ftpPort, userId, userPasswd, callback)` |
| `service/impl/DeviceServiceImpl.java` | 新增方法 | 委托给 `sipCommander.ftpServerConfigCmd()` |

**无需新增文件。** 所有变更均为现有类的增量添加。

### 5.1 SIPCommander.ftpServerConfigCmd

```java
public void ftpServerConfigCmd(Device device, String channelId,
    String ipv4Address, int ftpPort, String userId, String userPasswd,
    SipSubscribe.Event okEvent, SipSubscribe.Event errorEvent)
    throws InvalidArgumentException, SipException, ParseException
```

- 构建 `<Control>` XML，`CmdType=ServerCfgType`，`ServerType=ftpServerCfgType`
- 使用 `headerProvider.createMessageRequest(device, xml, ...)` 创建请求
- 使用 `sipSender.transmitRequest(ip, request, errorEvent, okEvent, null)` 发送

### 5.2 DeviceQuery Controller 端点

```java
@PostMapping("/ftp/config")
@Operation(summary = "下发FTP服务器配置")
public DeferredResult<WVPResult<String>> ftpConfig(@RequestBody FtpConfigVO ftpConfig)
```

- 使用 `DeferredResult<WVPResult<String>>` 异步等待
- 验证设备存在性和在线状态
- 设置 5 秒超时兜底

### 5.3 请求 VO (FtpConfigVO)

使用匿名内部类或直接在方法参数中接收，字段：

| 字段 | 类型 | 校验 |
|------|------|------|
| deviceId | String | @NotBlank |
| ipv4Address | String | @NotBlank |
| ftpPort | int | @Min(1) @Max(65535) |
| userId | String | @NotBlank |
| userPasswd | String | @NotBlank |

## 6. 信令交互时序

### 6.1 完整三方时序（设备注册 → 配置下发 → 文件上传）

```
执法终端 (ZX)              WVP-GB28181-Pro           执法仪设备管理系统        FTP 服务器
    │                           │                          │                    │
    │ ─── 1. SIP REGISTER ────▶ │                          │                    │
    │ ◀── 200 OK ───────────── │                          │                    │
    │                           │                          │                    │
    │                           │ ── 2. 设备上线通知 ────▶ │                    │
    │                           │    (HTTP callback)        │                    │
    │                           │                          │                    │
    │                           │                          │ ┌──────────────┐  │
    │                           │                          │ │ 查询该设备   │  │
    │                           │                          │ │ FTP 服务器   │  │
    │                           │                          │ │ 凭据信息     │  │
    │                           │                          │ └──────────────┘  │
    │                           │                          │                    │
    │                           │ ◀─ 3. POST /api/ftp/config ── │               │
    │                           │    {deviceId, ipv4Address,  │               │
    │                           │     ftpPort, userId,        │               │
    │                           │     userPasswd}             │               │
    │                           │                             │               │
    │                           │ ─── 4. SIP MESSAGE ──────▶ │               │
    │                           │  ServerCfgType/             │               │
    │                           │  ftpServerCfgType           │               │
    │                           │                             │               │
    │                           │ ◀── 5. SIP 200 OK ──────── │               │
    │                           │                             │               │
    │                           │ ── 6. HTTP 200 {code:0} ─▶ │               │
    │                           │                             │               │
    │ ┌──────────────────────┐  │                             │               │
    │ │ 解析 XML             │  │                             │               │
    │ │ 写入 FTPService      │  │                             │               │
    │ │ setIsReceive(true)   │  │                             │               │
    │ │ FtpProducer 被唤醒   │  │                             │               │
    │ └──────────────────────┘  │                             │               │
    │                           │                             │               │
    │ ─── 7. FTP/SFTP 上传 ──────────────────────────────────────────────────▶│
    │     (录像文件、缩略图)    │                             │               │
    │                           │                             │               │
    │   ※ 上传进度/完成仅通过 JT808 通道上报，不经过 GB28181                    │
```

### 6.2 SIP MESSAGE 详细内容

```
WVP 平台                        终端 (ZX)
    │                               │
    │─── SIP MESSAGE ─────────────▶│
    │    Content-Type: Application/MANSCDP+xml
    │    <Control>                  │
    │      <CmdType>ServerCfgType   │
    │      <SN>12345</SN>           │
    │      <DeviceID>...</DeviceID> │
    │      <ServerType>             │
    │        ftpServerCfgType       │
    │      </ServerType>            │
    │      <FtpServerCfgType>       │
    │        <Ipv4Address>...       │
    │        <FTPPort>...           │
    │        <UserId>...            │
    │        <UserPasswd>...        │
    │      </FtpServerCfgType>      │
    │    </Control>                 │
    │                               │ ├── 解析 XML
    │                               │ ├── FTPService.setFtpserviceIp()
    │                               │ ├── FTPService.setFtpservicePort()
    │                               │ ├── FTPService.setFtpserviceName()
    │                               │ ├── FTPService.setFTPServicePWD()
    │                               │ └── FTPService.setIsReceive(true)
    │                               │
    │◀── SIP 200 OK ───────────────│ (C++ SIP 栈自动回复)
    │                               │
    │ [HTTP 响应: code=0, 成功]      │
    │                               │
    │                               │ [FtpProducer 被唤醒]
    │                               │ [开始自动上传录像文件到 FTP]
    │    ※ FTP 上传过程不经过 GB28181 │
    │    ※ 上传进度/完成仅通过 JT808  │
```

## 7. 非功能性需求

| 项目 | 要求 |
|------|------|
| 接口认证 | 复用 WVP 现有 JWT 认证机制 |
| 并发安全 | 同一设备同时只允许一个配置下发请求 |
| 日志 | 记录下发目标设备 ID、FTP 地址、成功/失败结果 |
| 向后兼容 | 不影响现有 SIP 命令处理流程 |
| 传输协议 | 与设备注册时使用的传输协议一致（TCP/UDP） |

## 8. 限制与约束

1. **私有协议**：`ServerCfgType` / `ftpServerCfgType` 是 ZX 终端的私有扩展，非 GB/T 28181 标准。仅适用于已对接此私有协议的终端设备。
2. **单向确认**：只能确认 SIP MESSAGE 是否到达终端（SIP 200 OK），无法确认终端是否成功解析和应用了 FTP 配置。
3. **S3 配置**：本文档仅覆盖 FTP 配置下发。S3/云存储配置下发（`pictureServerCfgType`）可后续扩展。
