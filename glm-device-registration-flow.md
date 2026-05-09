# WVP-GB28181-Pro 终端设备注册流程分析

> 本文档详细分析了终端设备（摄像机、NVR 等）通过 GB/T 28181 SIP 协议注册到 WVP 系统的完整流程，以及 WVP 作为下级平台级联注册到上级平台的流程。

---

## 目录

- [一、概述](#一概述)
- [二、SIP 协议栈初始化](#二sip-协议栈初始化)
- [三、终端设备注册流程（设备→WVP）](#三终端设备注册流程设备wvp)
  - [1. SIP REGISTER 请求分发](#1-sip-register-请求分发)
  - [2. 解析设备 ID 与请求类型](#2-解析设备-id-与请求类型)
  - [3. 设备 ID 合法性校验](#3-设备-id-合法性校验)
  - [4. 加载设备记录](#4-加载设备记录)
  - [5. 注册续约快速路径（同 Call-ID）](#5-注册续约快速路径同-call-id)
  - [6. 确定认证密码](#6-确定认证密码)
  - [7. 第一次 REGISTER：401 挑战](#7-第一次-register401-挑战)
  - [8. 第二次 REGISTER：摘要认证校验](#8-第二次-register摘要认证校验)
  - [9. 返回 200 OK 并创建/更新设备](#9-返回-200-ok-并创建更新设备)
  - [10. 设备上线处理](#10-设备上线处理)
- [四、设备生命周期管理](#四设备生命周期管理)
  - [1. 心跳保活机制](#1-心跳保活机制)
  - [2. 设备超时下线检测](#2-设备超时下线检测)
  - [3. 设备下线处理](#3-设备下线处理)
  - [4. 通道目录同步](#4-通道目录同步)
  - [5. 定期维护任务](#5-定期维护任务)
- [五、级联注册流程（WVP→上级平台）](#五级联注册流程wvp上级平台)
  - [1. 平台实体与配置](#1-平台实体与配置)
  - [2. 注册调度机制](#2-注册调度机制)
  - [3. 发出 SIP REGISTER](#3-发出-sip-register)
  - [4. 处理 401 挑战与摘要认证](#4-处理-401-挑战与摘要认证)
  - [5. 平台上线/下线](#5-平台上线下线)
  - [6. 通道共享](#6-通道共享)
- [六、关键源码文件索引](#六关键源码文件索引)
- [七、配置参数说明](#七配置参数说明)

---

## 一、概述

本系统涉及两种注册场景：

| 场景 | 方向 | 协议 | 说明 |
|------|------|------|------|
| **设备注册** | 摄像机/NVR → WVP | SIP REGISTER | 终端设备按 GB/T 28181 标准注册到 WVP 服务器 |
| **级联注册** | WVP → 上级平台 | SIP REGISTER | WVP 作为下级平台注册到上级视频监控平台 |

两者都基于 RFC 3261 SIP REGISTER + HTTP Digest 认证机制，但角色相反。

---

## 二、SIP 协议栈初始化

WVP 服务启动时，`SipLayer`（实现 `CommandLineRunner`）初始化 SIP 监听：

**源码**：[SipLayer.java](src/main/java/com/genersoft/iot/vmp/gb28181/SipLayer.java)

```
启动流程:
1. 读取 sip.ip 配置（支持逗号分隔多 IP，留空则自动发现所有 IPv4 网卡）
2. 对每个监听 IP:
   ├── 创建 SipStackImpl（配置 NIO 消息处理、关闭自动 Dialog）
   ├── 安装 GbStringMsgParserFactory（国标专用 SIP 消息解析器）
   ├── 创建 TCP ListeningPoint（端口由 sip.port 决定，默认 5060）
   ├── 创建 UDP ListeningPoint（同端口）
   └── 为 TCP/UDP 各创建 SipProviderImpl，注册 SIPProcessorObserver 为监听器
```

**关键配置**（`application.yml` → `SipConfig`）：

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `sip.ip` | 自动检测 | SIP 监听 IP（逗号分隔多 IP） |
| `sip.port` | 5060 | SIP 监听端口（TCP+UDP） |
| `sip.domain` | — | SIP 域（同时作为 Digest 认证的 realm） |
| `sip.id` | — | WVP 自身的 20 位国标编码 |
| `sip.password` | — | 全局默认 SIP 认证密码 |
| `sip.registerTimeInterval` | 120 | 注册间隔（秒） |

---

## 三、终端设备注册流程（设备→WVP）

### 完整时序图

```
  终端设备                     WVP (SIPProcessorObserver)    RegisterRequestProcessor     DeviceServiceImpl       DeviceStatusManager
    │                                │                              │                            │                         │
    │── REGISTER (无Auth) ──────────>│                              │                            │                         │
    │                                │── processRequest() ─────────>│                            │                         │
    │                                │                              │── getDeviceByDeviceId() ──>│                         │
    │                                │                              │<── Device 或 null ─────────│                         │
    │                                │                              │                            │                         │
    │                                │                              │── 检查 Call-ID 匹配？ ─────│                         │
    │                                │                              │   不匹配（新注册）           │                         │
    │                                │                              │── 确定认证密码              │                         │
    │                                │                              │── 无 Authorization 头       │                         │
    │                                │                              │── 生成 401 + nonce          │                         │
    │<── 401 Unauthorized ───────────│<── transmitRequest() ───────│                            │                         │
    │                                │                              │                            │                         │
    │── REGISTER (带Authorization) ─>│                              │                            │                         │
    │                                │── processRequest() ─────────>│                            │                         │
    │                                │                              │── 校验 Digest 认证          │                         │
    │                                │                              │   通过                      │                         │
    │                                │                              │── 构建 200 OK               │                         │
    │<── 200 OK ─────────────────────│<── transmitRequest() ───────│                            │                         │
    │                                │                              │── 构建 SipTransactionInfo   │                         │
    │                                │                              │── online(device) ──────────>│                         │
    │                                │                              │                            │── add to Redis ZSet ────>│
    │                                │                              │                            │── INSERT/UPDATE DB       │
    │                                │                              │                            │── UPDATE Redis cache     │
    │                                │                              │                            │── deviceInfoQuery()      │
    │                                │                              │                            │── sync() 通道目录同步     │
    │                                │                              │                            │                         │
    │── NOTIFY (Keepalive) ──────────>│                              │                            │                         │
    │                                │── 分发到 KeepaliveHandler ──>│                            │                         │
    │<── 200 OK ─────────────────────│                              │                            │                         │
    │                                │                              │                            │<── 刷新过期时间 ─────────│
    │                                │                              │                            │                         │
    │  [注册过期 / 心跳超时]           │                              │                            │                         │
    │                                │                              │                            │                         │
    │                                │                              │         DeviceStatusManager.expirationCheck()          │
    │                                │                              │         发现 Redis ZSet 中过期条目                     │
    │                                │                              │         发布 DeviceOfflineEvent                        │
    │                                │                              │                            │<── onApplicationEvent() ─│
    │                                │                              │                            │── offline(device)        │
    │                                │                              │                            │── 清理订阅、释放SSRC      │
    │                                │                              │                            │── UPDATE DB/Redis        │
```

### 1. SIP REGISTER 请求分发

**源码**：[SIPProcessorObserver.java](src/main/java/com/genersoft/iot/vmp/gb28181/transmit/SIPProcessorObserver.java)

SIP 协议栈收到任何请求后调用 `processRequest(RequestEvent)`：

```
1. 从 Request 中提取方法名（如 "REGISTER"）
2. 在 requestProcessorMap 中查找对应处理器
3. 异步调用 processor.process(requestEvent)（@Async）
```

`RegisterRequestProcessor` 在 Spring 初始化时（`afterPropertiesSet()`）自注册：
```java
sipProcessorObserver.addRequestProcessor("REGISTER", this);
```

### 2. 解析设备 ID 与请求类型

**源码**：[RegisterRequestProcessor.java](src/main/java/com/genersoft/iot/vmp/gb28181/transmit/event/request/impl/RegisterRequestProcessor.java)

```java
// 从 From 头提取设备 ID（20 位国标编码）
FromHeader fromHeader = (FromHeader) request.getHeader(FromHeader.NAME);
SipUri uri = (SipUri) ((AddressImpl) fromHeader.getAddress()).getURI();
String deviceId = uri.getUser();

// 从 Expires 头判断注册 or 注销
boolean registerFlag = request.getExpires().getExpires() != 0;
// Expires != 0 → 注册
// Expires == 0 → 注销
```

**解析的 SIP 头**：

| SIP 头 | 用途 |
|--------|------|
| `From` | 提取设备 ID（SIP URI 的 user 部分） |
| `Expires` | 区分注册（非零）与注销（零） |
| `Authorization` | Digest 认证凭据（第二次 REGISTER 携带） |
| `Call-ID` | 匹配注册续约（相同 Call-ID = 同一对话续约） |
| `Via` | 确定传输协议（TCP/UDP）及提取远程地址（received/rport） |
| `Contact` | 在 200 OK 中原样返回 |

### 3. 设备 ID 合法性校验

**源码**：[GbCode.java](src/main/java/com/genersoft/iot/vmp/gb28181/bean/GbCode.java)

当 `userSetting.deviceIdStrict = true`（默认开启）时，校验设备 ID 必须为 20 位数字：

```
20 位编码结构:
├── 第 0-7 位  centerCode   行政区划编码
├── 第 8-9 位  industryCode 行业编码
├── 第 10-12 位 typeCode    类型编码（≤199 为真实设备）
├── 第 13 位   netCode      网络标识
└── 第 14-19 位 sn          序列号
```

校验失败直接返回 **403 Forbidden**。

### 4. 加载设备记录

**源码**：[DeviceServiceImpl.java](src/main/java/com/genersoft/iot/vmp/gb28181/service/impl/DeviceServiceImpl.java) → `getDeviceByDeviceId()`

采用 **Redis 优先、数据库兜底** 策略：

```
1. 查询 Redis 缓存（redisCatchStorage.getDevice）
2. 未命中 → 查询数据库（deviceMapper.getDeviceByDeviceId）
3. 数据库命中 → 回填 Redis 缓存
4. 均未命中 → 返回 null（新设备）
```

同时提取设备远程地址：

```
if sipUseSourceIpAsRemoteAddress = true:
    使用数据包实际源 IP/端口
else:
    优先使用 Via 头的 received/rport 参数
    回退到数据包源地址
```

### 5. 注册续约快速路径（同 Call-ID）

如果设备已存在 **且** 本次 REGISTER 的 `Call-ID` 与存储的 `device.sipTransactionInfo.callId` 相同，则视为同一对话内的注册续约，**跳过认证**：

```
if registerFlag = true:
    更新设备 IP、端口、transport、expires
    发送 200 OK
    调用 deviceService.online(device)

if registerFlag = false:
    调用 deviceService.offline(device)

直接返回，不执行后续认证流程
```

### 6. 确定认证密码

密码选取优先级：

```
已知设备（数据库有记录）:
    1. 设备专属密码（device.password）  ← 最高优先
    2. 全局密码（sipConfig.password）
    3. 均为空 → 不需要认证

新设备（数据库无记录）:
    1. 全局密码（sipConfig.password）
    2. 无全局密码 → 直接返回 403 Forbidden（拒绝未知设备）
```

### 7. 第一次 REGISTER：401 挑战

**源码**：[DigestServerAuthenticationHelper.java](src/main/java/com/genersoft/iot/vmp/gb28181/auth/DigestServerAuthenticationHelper.java) → `generateChallenge()`

当需要认证但请求中无 `Authorization` 头时：

```
1. 创建 401 Unauthorized 响应
2. 生成 WWW-Authenticate 头:
   ├── scheme:    "Digest"
   ├── realm:     sipConfig.getDomain()    ← SIP 域作为 realm
   ├── qop:       "auth"
   ├── nonce:     MD5(当前时间戳 + 随机数)
   └── algorithm: "MD5"
3. 通过 SipSender 发送响应（自动根据 Via 头选择 TCP/UDP 发送）
```

### 8. 第二次 REGISTER：摘要认证校验

**源码**：`DigestServerAuthenticationHelper.java` → `doAuthenticatePlainTextPassword()`

设备携带 `Authorization` 头重新发送 REGISTER，WVP 按 RFC 2617 校验：

```
1. 从 Authorization 头提取: realm, username, nonce, uri, qop, cnonce, nc, response

2. 计算 MD5 摘要:
   HA1 = MD5(username : realm : password)
   HA2 = MD5("REGISTER" : uri)

3. 根据 qop 计算期望值:
   if qop = "auth":
       expected = MD5(HA1 : nonce : nc : cnonce : qop : HA2)
   else:
       expected = MD5(HA1 : nonce : HA2)

4. 比较 expected 与 response:
   匹配 → 认证通过
   不匹配 → 返回 403 Forbidden（原因: "wrong password"）
```

### 9. 返回 200 OK 并创建/更新设备

认证通过后：

```
1. 构建 200 OK 响应
   ├── 可选添加 Date 头（用于设备时间同步，除非 disableDateHeader=true）
   ├── 回传 Contact 头
   └── 回传 Expires 头

2. 创建或更新 Device 对象:
   新设备默认值:
   ├── streamMode = "TCP-PASSIVE"
   ├── charset = "GB2312"
   ├── geoCoordSys = "WGS84"
   ├── mediaServerId = "auto"
   └── onLine = false

   所有设备:
   ├── serverId = 当前 WVP 实例 ID
   ├── ip/port/hostAddress = 远程地址
   ├── localIp = SIP 请求的本地地址
   ├── transport = TCP 或 UDP（从 Via 头判断）
   └── expires = Expires 头的值

3. 发送 200 OK

4. 记录注册时间戳

5. 从 200 OK 响应中提取 SipTransactionInfo（callId, fromTag, toTag, viaBranch）

6. 根据注册/注销调用:
   注册 → deviceService.online(device)
   注销 → deviceService.offline(device)
```

### 10. 设备上线处理

**源码**：`DeviceServiceImpl.java` → `online(Device device)`

根据设备状态分三种情况：

#### 情况 A：新设备（数据库无记录）

```
1. 设置 onLine = true, createTime, updateTime
2. INSERT into wvp_device                    ← 写入数据库
3. UPDATE Redis cache                         ← 更新缓存
4. 发送 SIP MESSAGE 查询设备信息               ← deviceInfoQuery()
   （获取设备名称、厂商、型号、固件版本）
5. 发送 SIP MESSAGE 查询设备配置               ← deviceConfigQuery()
6. [可选] 添加移动位置订阅
7. 发送 SIP MESSAGE 目录查询                   ← sync(device)
   （同步设备通道列表）
```

#### 情况 B：已有设备，之前离线

```
1. 设置 onLine = true, updateTime
2. UPDATE wvp_device                          ← 更新数据库
3. UPDATE Redis cache
4. [条件] syncChannelOnDeviceOnline=true 时重新同步通道
5. 恢复已配置的订阅（目录、移动位置、报警）
6. [可选] 发送 Redis 设备状态通知
```

#### 情况 C：已有设备，已在线（注册续约）

```
1. UPDATE wvp_device
2. UPDATE Redis cache
3. 若设备通道数为 0，触发 sync() 重新同步
```

#### 所有情况的最后一步：注册过期定时器

```java
long expiresTime = Math.min(
    device.getExpires(),                          // 注册有效期
    device.getHeartBeatInterval() * device.getHeartBeatCount()  // 心跳超时
) * 1000L;
deviceStatusManager.add(device.getDeviceId(), expiresTime + System.currentTimeMillis());
```

将设备加入 Redis 有序集合（score = 过期时间戳），供定时任务检测超时。

---

## 四、设备生命周期管理

### 1. 心跳保活机制

**源码**：[KeepaliveNotifyMessageHandler.java](src/main/java/com/genersoft/iot/vmp/gb28181/transmit/event/request/impl/message/notify/cmd/KeepaliveNotifyMessageHandler.java)

注册成功后，设备每隔 `heartBeatInterval`（默认 60 秒）发送 SIP NOTIFY（CmdType=Keepalive）：

```
1. WVP 立即回复 200 OK
2. 检测设备 IP/端口是否变化，变化则更新
3. 记录 keepaliveTimeStamp = 当前时间
4. 设备已在线 → 刷新 DeviceStatusManager 中的过期时间
5. 设备离线 + gbDeviceOnline=1 → 调用 deviceService.online() 恢复上线
6. [每 10 秒] 批量更新 Redis 中的心跳时间戳
```

心跳超时计算：
```
超时时间 = min(expires, heartBeatInterval × heartBeatCount)
默认值 = min(注册有效期, 60 × 3) = min(注册有效期, 180秒)
```

### 2. 设备超时下线检测

**源码**：[DeviceStatusManager.java](src/main/java/com/genersoft/iot/vmp/gb28181/task/deviceStatus/DeviceStatusManager.java)

使用 **Redis 有序集合** 作为定时器：

```
Redis Key:    VMP_DEVICE_EXPIRES_{serverId}
Member:       设备 ID
Score:        过期时间戳（毫秒）
```

**每秒执行**过期检测：

```
expirationCheck() [每 1 秒]:
1. Redis ZRANGEBYSCORE key 0 {当前时间戳} → 获取所有已过期设备 ID
2. ZREM 移除过期条目
3. 启动虚拟线程发布 DeviceOfflineEvent
```

### 3. 设备下线处理

**源码**：`DeviceServiceImpl.java` → `offline()` / `onApplicationEvent()`

```
DeviceOfflineEvent [异步监听]
│
└── offline(deviceList)
    ├── 1. device.onLine = false
    ├── 2. cleanOfflineDevice(device):
    │   ├── 移除目录订阅、移动位置订阅、报警订阅
    │   ├── 释放所有 SSRC 资源
    │   ├── 清理语音广播会话
    │   └── 从 DeviceStatusManager 移除
    ├── 3. UPDATE Redis cache
    ├── 4. UPDATE wvp_device SET on_line = false
    ├── 5. [可选] 发送 Redis 状态通知
    └── 6. UPDATE wvp_device_channel SET status='OFF'
            WHERE data_device_id = ?
         + 发布 ChannelEvent(OFF) 通知上级平台
```

### 4. 通道目录同步

**源码**：[CatalogResponseMessageHandler.java](src/main/java/com/genersoft/iot/vmp/gb28181/transmit/event/request/impl/message/response/cmd/CatalogResponseMessageHandler.java)

设备注册后 WVP 主动查询通道目录：

```
sync(device) 调用链:
1. 生成随机 SN（序列号）
2. catalogResponseMessageHandler.setChannelSyncReady(device, sn)
   → 初始化同步会话状态
3. sipCommander.catalogQuery(device, sn)
   → 发送 SIP MESSAGE 查询目录

设备返回 Catalog 响应:
4. 解析 XML，提取 DeviceList、SumNum、SN
5. 按通道 ID 长度分类:
   ├── ≤8 位 → 行政区域（Region）
   ├── 20 位  → 业务分组/虚拟组织（Group）
   └── 其他   → 通道（DeviceChannel）
6. 累积到 CatalogDataManager（内存 + Redis）
7. 收齐后执行 resetChannels():
   ├── 已有通道 → 更新
   ├── 新通道   → 插入
   └── 缺失通道 → 删除
8. 批量写入 wvp_device_channel 表（每批 500 条）
```

**会话超时保护**：

| 状态 | 超时 | 处理 |
|------|------|------|
| ready（等待首个响应） | 2 分钟 | 标记失败 |
| running（接收数据中） | 5 秒无新消息 | 强制保存已收到数据 |
| end（已完成） | 30 秒 | 清理会话 |

### 5. 定期维护任务

| 任务 | 周期 | 说明 |
|------|------|------|
| 心跳超时检测 | 1 秒 | DeviceStatusManager.expirationCheck() |
| Redis 心跳批量更新 | 10 秒 | KeepaliveNotifyMessageHandler |
| 订阅丢失恢复 | 10 秒 | 检查并重新建立缺失的订阅 |
| 数据库状态校准 | 6 小时 | 交叉比对 Redis 与数据库，修正不一致的在线状态 |

---

## 五、级联注册流程（WVP→上级平台）

### 时序图

```
  WVP (下级)                      上级平台
    │                                │
    │── REGISTER (无Auth) ──────────>│
    │<── 401 Unauthorized ───────────│
    │                                │
    │── REGISTER (带Digest Auth) ───>│
    │<── 200 OK ─────────────────────│
    │                                │
    │── NOTIFY (Keepalive) ─────────>│
    │<── 200 OK ─────────────────────│
    │                                │
    │  [expires 到期前 500ms]         │
    │── REGISTER (续约) ────────────>│
    │<── 200 OK ─────────────────────│
    │                                │
    │── MESSAGE (Catalog NOTIFY) ───>│   ← 推送通道变更
    │<── 200 OK ─────────────────────│
```

### 1. 平台实体与配置

**源码**：[Platform.java](src/main/java/com/genersoft/iot/vmp/gb28181/bean/Platform.java)

数据库表 `wvp_platform`，关键字段：

| 字段 | 说明 |
|------|------|
| `serverGBId` | 上级平台的国标编码 |
| `serverIp` / `serverPort` | 上级 SIP 服务器地址 |
| `deviceGBId` | **WVP 自身的国标编码**（作为设备 ID 向上级注册） |
| `deviceIp` / `devicePort` | WVP 的 SIP 地址 |
| `username` / `password` | SIP Digest 认证凭据 |
| `expires` | 注册有效期（秒） |
| `keepTimeout` | 心跳超时时间（秒） |
| `transport` | 传输协议（UDP/TCP） |
| `enable` | 是否启用级联 |
| `status` | 当前在线状态 |
| `autoPushChannel` | 是否自动推送通道变更 |
| `catalogGroup` | 每次 Catalog NOTIFY 的批量大小 |

### 2. 注册调度机制

**源码**：[PlatformServiceImpl.java](src/main/java/com/genersoft/iot/vmp/gb28181/service/impl/PlatformServiceImpl.java)

三个调度任务协同工作：

#### a) 状态丢失检测 — 每 20 秒

```
statusLostCheck() [每 20 秒]:
  遍历所有启用的平台:
  ├── 注册任务和 keepalive 任务都存在 → 健康，跳过
  ├── 注册任务存在但 keepalive 不存在 → 先注销再重新注册
  └── 注册任务不存在 → 发起新的注册
```

#### b) 集群故障转移 — 每 2 秒

```
execute() [每 2 秒]:
  if autoRegisterPlatform = false → 跳过
  遍历属于其他 WVP 实例的平台:
  └── 如果该实例在 Redis 中无心跳 → 接管其平台注册
```

#### c) 延迟队列到期 — 每 500ms

```
PlatformRegisterTaskRunner:
  ├── expirationCheckForRegister() → 处理注册到期（触发续约）
  └── expirationCheckForKeepalive() → 处理心跳到期

任务信息持久化到 Redis:
  Key: VMP_PLATFORM_STATUS_{serverId}_{platformServerId}
  TTL: 与注册过期时间匹配（WVP 重启后可恢复）
```

### 3. 发出 SIP REGISTER

**源码**：[SIPCommanderForPlatform.java](src/main/java/com/genersoft/iot/vmp/gb28181/transmit/cmd/impl/SIPCommanderForPlatform.java)、[SIPRequestHeaderPlarformProvider.java](src/main/java/com/genersoft/iot/vmp/gb28181/transmit/cmd/SIPRequestHeaderPlarformProvider.java)

构建 SIP REGISTER 请求：

```
Request-URI:  sip:{serverGBId}@{serverIp}:{serverPort}
Via:          SIP/2.0/{transport} {deviceIp}:{devicePort};branch=z9hG4bK-{random}
From:         sip:{deviceGBId}@{sipConfig.domain};tag={fromTag}
To:           sip:{deviceGBId}@{sipConfig.domain};tag={toTag}
Call-ID:      {callId}@{deviceIp}
CSeq:         {自增序列} REGISTER
Contact:      sip:{deviceGBId}@{deviceIp}:{devicePort}
Expires:      {expires}  ← 注册有效期，注销时设为 0
Max-Forwards: 70
Content-Length: 0

[第二次携带 Authorization 头]:
Authorization: Digest username="{deviceGBId}", realm="{realm}",
    nonce="{nonce}", uri="{requestURI}", response="{MD5摘要}",
    qop=auth, nc=00000001, cnonce="{随机UUID}"
```

### 4. 处理 401 挑战与摘要认证

**源码**：[RegisterResponseProcessor.java](src/main/java/com/genersoft/iot/vmp/gb28181/transmit/event/response/impl/RegisterResponseProcessor.java)

```
收到上级平台响应:

401 Unauthorized:
  1. 提取 WWW-Authenticate 头（realm, nonce, qop）
  2. 计算 RFC 2617 Digest 摘要:
     HA1 = MD5(deviceGBId : realm : password)
     HA2 = MD5("REGISTER" : requestURI)
     response = MD5(HA1 : nonce : nc : cnonce : qop : HA2)
  3. 构建带 Authorization 头的新 REGISTER 重新发送

200 OK:
  if expires > 0:
     调用 platformService.online(platform)    ← 上级接受注册
  if expires == 0:
     调用 platformService.offline(platform)   ← 注销成功
```

### 5. 平台上线/下线

**源码**：`PlatformServiceImpl.java` → `online()` / `offline()`

#### 平台上线

```
online(platform, sipTransactionInfo):
1. 创建 PlatformRegisterTask
   延迟 = expires * 1000 - 500ms
   到期回调 → registerExpire() → sendRegister() 刷新注册
2. 创建 PlatformKeepaliveTask
   延迟 = keepTimeout * 1000
   到期回调 → keepaliveExpire()
   连续失败 3 次后平台下线
3. UPDATE wvp_platform SET status = true
4. if autoPushChannel = true:
     创建模拟目录订阅（支持主动推送通道变更）
```

#### 平台下线

```
offline(platform):
1. 移除注册和 keepalive 任务
2. 清除目录和移动位置订阅
3. UPDATE wvp_platform SET status = false
4. stopAllPush() → 释放 SSRC，停止所有媒体推流
```

### 6. 通道共享

**源码**：[PlatformChannelServiceImpl.java](src/main/java/com/genersoft/iot/vmp/gb28181/service/impl/PlatformChannelServiceImpl.java)

两种通道共享模式：

| 模式 | 触发方式 | 机制 |
|------|---------|------|
| **拉取模式** | 上级平台发送 Catalog 查询 | WVP 响应 SIP MESSAGE 包含通道 XML 列表 |
| **推送模式** | 通道变更事件 | WVP 主动发送 SIP NOTIFY（CmdType=Catalog），按 catalogGroup 批量发送 |

推送触发条件（通过 Spring 的 `ChannelEvent` 监听器）：
- 通道添加/更新/删除
- 通道上线/下线

---

## 六、关键源码文件索引

### SIP 基础设施

| 文件 | 路径 | 说明 |
|------|------|------|
| `SipLayer` | `gb28181/SipLayer.java` | SIP 协议栈初始化（TCP+UDP 监听） |
| `SIPProcessorObserver` | `gb28181/transmit/SIPProcessorObserver.java` | SIP 请求/响应分发器 |
| `SIPSender` | `gb28181/transmit/SIPSender.java` | SIP 消息发送（自动选择 TCP/UDP） |
| `SipConfig` | `conf/SipConfig.java` | SIP 配置绑定 |
| `DefaultProperties` | `gb28181/conf/DefaultProperties.java` | SIP 栈默认属性 |
| `DigestServerAuthenticationHelper` | `gb28181/auth/DigestServerAuthenticationHelper.java` | Digest 认证工具 |

### 设备注册（设备→WVP）

| 文件 | 路径 | 说明 |
|------|------|------|
| `RegisterRequestProcessor` | `gb28181/transmit/event/request/impl/RegisterRequestProcessor.java` | REGISTER 请求处理器 |
| `DeviceServiceImpl` | `gb28181/service/impl/DeviceServiceImpl.java` | 设备上线/下线核心逻辑 |
| `Device` | `gb28181/bean/Device.java` | 设备实体 |
| `DeviceMapper` | `gb28181/dao/DeviceMapper.java` | 设备数据库映射 |
| `GbCode` | `gb28181/bean/GbCode.java` | 国标编码校验 |
| `SipTransactionInfo` | `gb28181/bean/SipTransactionInfo.java` | SIP 事务标识（callId 等） |

### 设备生命周期

| 文件 | 路径 | 说明 |
|------|------|------|
| `DeviceStatusManager` | `gb28181/task/deviceStatus/DeviceStatusManager.java` | Redis 有序集合超时检测 |
| `KeepaliveNotifyMessageHandler` | `gb28181/transmit/event/request/impl/message/notify/cmd/KeepaliveNotifyMessageHandler.java` | 心跳处理 |
| `CatalogResponseMessageHandler` | `gb28181/transmit/event/request/impl/message/response/cmd/CatalogResponseMessageHandler.java` | 目录查询响应处理 |
| `CatalogDataManager` | `gb28181/session/CatalogDataManager.java` | 目录同步会话管理 |
| `DeviceOnlineEvent` | `gb28181/event/device/DeviceOnlineEvent.java` | 设备上线事件 |
| `DeviceOfflineEvent` | `gb28181/event/device/DeviceOfflineEvent.java` | 设备下线事件 |
| `EventPublisher` | `gb28181/event/EventPublisher.java` | 事件发布器 |

### 级联注册（WVP→上级平台）

| 文件 | 路径 | 说明 |
|------|------|------|
| `PlatformServiceImpl` | `gb28181/service/impl/PlatformServiceImpl.java` | 平台注册调度与生命周期 |
| `Platform` | `gb28181/bean/Platform.java` | 平台实体 |
| `RegisterResponseProcessor` | `gb28181/transmit/event/response/impl/RegisterResponseProcessor.java` | 注册响应处理（401→重注册） |
| `SIPCommanderForPlatform` | `gb28181/transmit/cmd/impl/SIPCommanderForPlatform.java` | 向上级发送 SIP 命令 |
| `SIPRequestHeaderPlarformProvider` | `gb28181/transmit/cmd/SIPRequestHeaderPlarformProvider.java` | 构建 SIP REGISTER 请求头 |
| `PlatformRegisterTaskRunner` | `gb28181/task/platformStatus/PlatformRegisterTaskRunner.java` | 延迟队列注册/心跳任务 |
| `PlatformChannelServiceImpl` | `gb28181/service/impl/PlatformChannelServiceImpl.java` | 通道共享管理 |

---

## 七、配置参数说明

### SIP 配置（application.yml → sip.*）

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `sip.ip` | 自动检测 | SIP 监听 IP |
| `sip.port` | 5060 | SIP 监听端口（TCP+UDP） |
| `sip.domain` | 必填 | SIP 域（Digest 认证 realm） |
| `sip.id` | 必填 | WVP 的 20 位国标编码 |
| `sip.password` | — | 全局默认 SIP 认证密码 |
| `sip.registerTimeInterval` | 120 | 默认注册间隔（秒） |
| `sip.ptzSpeed` | 50 | 云台控制速度 |
| `sip.alarm` | true | 是否存储报警信息 |
| `sip.timeout` | 1000 | 命令超时（毫秒） |

### 用户设置（application.yml → user-settings.*）

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `deviceIdStrict` | true | 是否严格校验设备 ID（20 位数字） |
| `gbDeviceOnline` | 1 | 离线设备收到心跳是否自动上线（0=必须重新注册，1=心跳触发上线） |
| `syncChannelOnDeviceOnline` | false | 离线设备重新上线时是否总是同步通道 |
| `deviceStatusNotify` | true | 设备状态变化时是否发送 Redis 通知 |
| `subscribeMobilePosition` | false | 新设备上线时是否自动订阅移动位置 |
| `sipUseSourceIpAsRemoteAddress` | — | 是否使用数据包源 IP 作为设备远程地址 |
| `disableDateHeader` | false | 是否在 200 OK 中禁用 Date 头 |
| `autoRegisterPlatform` | — | 是否自动接管其他 WVP 实例的级联注册 |

### 设备默认值

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `heartBeatInterval` | 60 秒 | 心跳间隔 |
| `heartBeatCount` | 3 | 心跳超时次数 |
| `streamMode` | TCP-PASSIVE | 流传输模式 |
| `charset` | GB2312 | 字符编码 |
| `geoCoordSys` | WGS84 | 地理坐标系 |
