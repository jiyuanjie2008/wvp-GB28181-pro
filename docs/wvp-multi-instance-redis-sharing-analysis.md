# WVP 多实例共享 Redis 分析报告

## 1. 概述

本文档分析 WVP-GB28181-Pro 项目中 Redis 的使用方式，评估多个 WVP 实例共用同一个 Redis 实例时所需的改动范围。

**结论：改动量极小。WVP 已经为多实例共享 Redis 做好了设计。**

---

## 2. Redis 在 WVP 中的角色

Redis 在 WVP 中承担以下职责：

- **分布式状态存储** — 设备信息、SIP 会话、流媒体状态
- **实例间通信总线** — Pub/Sub 消息、RPC 调用
- **缓存层** — Spring Cache、API Key 缓存
- **原子计数器** — SIP CSEQ、RTP 端口分配
- **集群协调** — 服务器发现、媒体服务器负载均衡

---

## 3. Redis Key 完整清单与隔离分析

所有 Key 常量定义在 `com.genersoft.iot.vmp.common.VideoManagerConstants`。

### 3.1 已按 serverId 隔离的 Key（无需改动）

| Key 模式 | Redis 类型 | 用途 |
|----------|-----------|------|
| `VMP_SIGNALLING_SERVER_INFO_{serverId}` | STRING (TTL) | WVP 实例信息 |
| `VMP_SIGNALLING_STREAM_{serverId}_{TYPE}_{app}_{streamId}_{mediaServerId}` | STRING | 信令流关联 |
| `VMP_MEDIA_SERVER_INFO:{serverId}` | HASH (field=mediaServerId) | 媒体服务器信息 |
| `VMP_ONLINE_MEDIA_SERVERS:{serverId}` | ZSET (score=负载) | 在线媒体服务器 |
| `VMP_SIP_CSEQ_{serverId}` | STRING (INCR) | SIP CSEQ 计数器 |
| `VMP_SSRC_INFO_{serverId}_{mediaServerId}` | SET | SSRC 资源池 |
| `VM_SEND_RTP_PORT:{serverId}:{mediaServerId}` | STRING (AtomicInteger) | RTP 端口分配 |
| `VMP_DEVICE_EXPIRES_{serverId}` | ZSET (score=过期时间) | 设备过期管理 |
| `VMP_SYSTEM_INFO_CPU_{serverId}` | LIST (max 30) | CPU 监控数据 |
| `VMP_SYSTEM_INFO_MEM_{serverId}` | LIST (max 30) | 内存监控数据 |
| `VMP_SYSTEM_INFO_NET_{serverId}` | LIST (max 30) | 网络监控数据 |
| `VMP_SYSTEM_INFO_DISK_{serverId}` | STRING | 磁盘监控数据 |
| `WVP_STREAM_GPS_MSG_{serverId}` | HASH (TTL 60s) | GPS 位置消息 |
| `INVITE_INFO_1078_POSITION:{serverId}` | LIST | JT1078 位置队列 |
| `VMP_SIP_INVITE_SESSION_INFO:CALL_ID:{serverId}` | HASH (field=callId) | SIP INVITE 会话 (CallID 索引) |
| `VMP_SIP_INVITE_SESSION_INFO:STREAM:{serverId}` | HASH (field=app+stream) | SIP INVITE 会话 (Stream 索引) |
| `VMP_SIP_SUBSCRIBE_` + serverId | STRING | SIP 订阅状态 |

### 3.2 按 deviceId/streamId/channelId 隔离的 Key（天然无冲突）

| Key 模式 | Redis 类型 | 隔离维度 |
|----------|-----------|---------|
| `VMP_DEVICE_INFO` | HASH (field=deviceId) | 设备 ID |
| `VMP_DEVICE_KEEPALIVE:{deviceId}` | LIST (max 100) | 设备 ID |
| `VMP_DEVICE_REGISTER:{deviceId}` | LIST (max 100) | 设备 ID |
| `VMP_GB_INVITE_INFO` | HASH (field=channelId+stream) | 通道+流 |
| `GB_RECORD_INFO_RES_{channelId}{sn}` | HASH | 通道+序号 |
| `GB_RECORD_INFO_RES_COUNT:{channelId}{sn}` | STRING | 通道+序号 |
| `VMP_SEND_RTP_INFO:STREAM:{stream}` | HASH (field=targetId) | 流 ID |
| `VMP_SEND_RTP_INFO:CHANNEL:{channelId}` | HASH (field=targetId) | 通道 ID |
| `VMP_MEDIA_STREAM_AUTHORITY` | HASH (field=app_stream) | 应用+流 |
| `VMP_PUSH_STREAM_LIST_{app}_{stream}` | STRING | 应用+流 |
| `VMP_WAITE_SEND_PUSH_STREAM:{app}_{stream}` | STRING | 应用+流 |
| `VMP_START_SEND_PUSH_STREAM:{app}_{stream}` | STRING (Pub/Sub) | 应用+流 |
| `VMP_RTP_AUTHENTICATE:{streamId}` | STRING (TTL 60s) | 流 ID |
| `VMP_CATALOG_DATA` | HASH (field=deviceId:channelDeviceId:sn) | 设备+通道+序号 |
| `VMP_SUBSCRIBE_OVERDUE:catalog:{platformId}` | STRING (TTL) | 平台 ID |
| `VMP_SUBSCRIBE_OVERDUE:mobilePosition:{platformId}` | STRING (TTL) | 平台 ID |
| `task_broadcast_waite_invite_{deviceId}` | STRING | 设备 ID |
| `INVITE_INFO_1078_PLAY:{phoneNumber}:{channelId}` | STRING | 手机号+通道 |
| `INVITE_INFO_1078_PLAYBACK:{phoneNumber}:{channelId}` | STRING | 手机号+通道 |
| `INVITE_INFO_1078_TALK:{phoneNumber}:{channelId}` | STRING | 手机号+通道 |

### 3.3 全局共享 Key（跨实例共用，无冲突）

| Key 模式 | Redis 类型 | 用途 | 共享影响 |
|----------|-----------|------|---------|
| `VMP_SERVER_LIST` | ZSET (member=serverId) | 所有 WVP 实例列表 | **设计为多实例共享** |
| `VMP_SEND_RTP_INFO:CALL_ID:` | HASH (field=callId) | SendRtp 的 CallID 索引 | callId 全局唯一，无冲突 |
| `userApiKey::{id}` | STRING (Spring Cache) | API Key 缓存 | 共享缓存，行为正确 |

### 3.4 第三方集成 Key（SyServiceImpl）

| Key | Redis 类型 | 用途 |
|-----|-----------|------|
| `SYSTEM_ACCESS_TOKEN` | STRING | 管理员认证 Token |
| `SYSTEM_SM4_KEY` | STRING | SM4 加密密钥 |
| `SYSTEM_APPKEY` | STRING (JSON) | 应用密钥配置 |
| `sys_INTERFACE_VALID_TIME` | STRING (JSON) | 接口超时配置 |
| `interfaceConfig1` | STRING (JSON) | 地图瓦片服务配置 |
| `machineInfo` | STRING (JSON) | 机器图标数据 |
| `VMP_FTP_USER_{username}` | STRING (TTL 10min) | FTP 临时用户会话 |

> **注意**：这些第三方集成 Key 是全局单例。如果多个 WVP 实例同时运行第三方集成模块，需要确认业务上是否允许共享这些配置。

---

## 4. Redis Pub/Sub 通道清单

通道注册在 `RedisMsgListenConfig.java`。

### 4.1 系统通道

| 通道名 | 发布者 | 监听者 | 用途 |
|--------|--------|--------|------|
| `VM_MSG_GPS` | 外部/GPS 服务 | `RedisGpsMsgListener` | GPS 消息 |
| `alarm_receive` | `RedisCatchStorageImpl` | `RedisAlarmMsgListener` | 报警订阅通知 |
| `VM_MSG_PUSH_STREAM_STATUS_CHANGE` | 外部 | `RedisPushStreamStatusMsgListener` | 推流状态变更 |
| `VM_MSG_PUSH_STREAM_LIST_CHANGE` | 外部 | `RedisPushStreamListMsgListener` | 推流列表变更 |
| `VM_MSG_STREAM_PUSH_CLOSE` | `RedisCatchStorageImpl` | `RedisCloseStreamMsgListener` | 关闭推流 |
| `VM_MSG_STREAM_PUSH_RESPONSE` | 外部 | `RedisPushStreamResponseListener` | 推流响应 |
| `VM_MSG_GROUP_LIST_RESPONSE` | 外部 | `RedisGroupMsgListener` | 分组列表响应 |
| `VM_MSG_GROUP_LIST_CHANGE` | 外部 | `RedisGroupChangeListener` | 分组变更 |
| `WVP_REDIS_REQUEST_CHANNEL_KEY` | `RedisRpcConfig` | `RedisRpcConfig` | Redis RPC 请求/响应 |

### 4.2 仅发布通道（无专用监听器注册）

| 通道名 | 用途 |
|--------|------|
| `WVP_MSG_STREAM_CHANGE_{type}` | 流变更通知 |
| `VM_MSG_STREAM_PUSH_REQUESTED` | 推流请求 |
| `VM_MSG_STREAM_START_PLAY_NOTIFY` | 开始播放通知 |
| `VM_MSG_STREAM_STOP_PLAY_NOTIFY` | 停止播放通知 |
| `VM_MSG_STREAM_PUSH_CLOSE_REQUESTED` | 关闭推流请求 |
| `VM_MSG_GET_ALL_ONLINE_REQUESTED` | 获取所有在线状态 |
| `device` | 设备/通道状态变更 |
| `VMP_START_SEND_PUSH_STREAM:{app}_{stream}` | 启动推流发送 |

### 4.3 第三方集成通道（SyServiceImpl）

| 通道名 | 用途 |
|--------|------|
| `REDIS_CHANNEL_MESSAGE` | 通道信息变更通知 |
| `REDIS_MEMBER_STATUS_MESSAGE` | 成员状态变更通知 |
| `REDIS_GPS_MESSAGE` | 移动设备 GPS 数据 |

---

## 5. Redis RPC 机制

WVP 内置了一套基于 Redis Pub/Sub 的 RPC 框架，用于跨实例通信。

- **实现文件**：`conf/redis/RedisRpcConfig.java`
- **通道**：`WVP_REDIS_REQUEST_CHANNEL_KEY`
- **消息格式**：携带 `fromId`（源实例 serverId）和 `toId`（目标实例 serverId）
- **支持模式**：同步请求/响应（带超时）、异步回调

已注册的 RPC Controller（`service/redisMsg/control/`）：

| Controller | 功能 |
|-----------|------|
| `RedisRpcDeviceController` | 设备操作 |
| `RedisRpcDevicePlayController` | 设备播放 |
| `RedisRpcChannelPlayController` | 通道播放 |
| `RedisRpcPlatformController` | 平台级联 |
| `RedisRpcStreamPushController` | 推流控制 |
| `RedisRpcStreamProxyController` | 流代理 |
| `RedisRpcSendRtpController` | 发送 RTP |
| `RedisRpcCloudRecordController` | 云端录像 |
| `RedisRpcGbDeviceController` | GB 设备操作 |

---

## 6. Redis 操作类型汇总

| 操作类型 | 使用位置 | 用途 |
|---------|---------|------|
| STRING get/set | `RedisCatchStorageImpl`, `SSRCFactory`, `RtpServerServiceImpl`, `UserManager`, `SubscribeHolder`, `SyServiceImpl` | 实例信息、流信息、FTP 用户、订阅状态、RTP 鉴权、第三方配置 |
| HASH put/get/delete | `RedisCatchStorageImpl`, `SendRtpServerServiceImpl`, `SipInviteSessionManager`, `CatalogDataManager`, `MediaServerServiceImpl` | 设备缓存、RTP 发送信息、SIP 会话、目录同步、媒体服务器、流授权 |
| LIST push/pop/range | `RedisCatchStorageImpl` (CPU/内存/网络), `jt1078ServiceImpl` (位置), `DeviceStatusManager` | 系统指标 (max 30 条)、JT1078 位置、设备心跳/注册时间戳 |
| SET add/pop/size | `SSRCFactory` | SSRC 池管理 |
| ZSET add/remove/range | `RedisCatchStorageImpl` (服务器列表), `MediaServerServiceImpl`, `DeviceStatusManager` | 服务器发现、媒体服务器负载均衡、设备过期追踪 |
| INCR/原子计数 | `RedisCatchStorageImpl.getCSEQ`, `SendRtpServerServiceImpl.getNextPort` | SIP CSEQ 计数、RTP 端口分配 |
| KEYS SCAN | `RedisUtil.scan` → `RedisCatchStorageImpl`, `MediaServerServiceImpl` | 按模式查找流 Key、媒体服务器 Key |
| PUB/SUB | `RedisCatchStorageImpl`, `RedisRpcConfig`, `SyServiceImpl` | 实例间消息、RPC、流变更事件、报警通知 |
| @Cacheable/@CacheEvict | `UserApiKeyServiceImpl` | API Key 缓存 |

---

## 7. 配置要求

### 7.1 当前 Redis 配置

```yaml
spring:
  data:
    redis:
      host: 127.0.0.1
      port: 6379
      database: 6    # 默认使用 database 6
      password: (可选)
      timeout: 10000
```

### 7.2 `serverId` 与 SIP ID 的区别

多实例部署时容易混淆两个 ID，它们是完全独立的：

| ID | 配置项 | 默认值 | 定义位置 | 用途 |
|---|---|---|---|---|
| **serverId** | `user-settings.server-id` | `000000` | `UserSetting.java` | Redis Key 隔离前缀、实例间 RPC 寻址 |
| **SIP ID** | `sip.id` | `34020000002000000001` | `SipConfig.java` | GB28181 SIP 服务器身份（20 位国标编码） |

- `serverId` 是一个简短的自定义标识符，**与 GB28181 协议无关**，仅用于 WVP 内部的 Redis Key 区分和跨实例通信
- 多实例共享 Redis 时，只需确保每个实例的 `user-settings.server-id` 值不同即可（例如 `wvp-1`、`wvp-2`），不需要改动 SIP 配置
- SIP ID 在多实例场景中通常也保持不同，但那是 GB28181 协议层面的要求，与 Redis 共享无关

### 7.3 数据库中的 `server_id` 列

WVP 的 MySQL 数据库中，部分表包含 `server_id` 列，用于多实例共用同一个 MySQL 时区分数据归属：

| 表 | `server_id` 列 | 用途 |
|---|---|---|
| `wvp_media_server` | `server_id` | 标记该媒体服务器属于哪个 WVP 实例 |
| `wvp_stream_push` | `server_id` | 标记该推流记录属于哪个 WVP 实例 |
| `wvp_cloud_record` | `server_id` | 标记该云录像属于哪个 WVP 实例 |

以 `MediaServerMapper` 为例，几乎所有查询都带 `server_id` 条件：

```java
// 只查自己的媒体服务器
"SELECT * FROM wvp_media_server where server_id = #{serverId}"
// 只删自己的
"DELETE FROM wvp_media_server WHERE id=#{id} and server_id = #{serverId}"
```

**`server_id` 列的目的不仅仅是性能，主要是正确性：**

1. **设备归属判定** — 摄像头通过 SIP 注册到某个 WVP 实例后，只有该实例知道如何向这个设备发送 SIP INVITE、PTZ 控制等指令。如果另一个 WVP 实例误操作不属于自己的设备，会导致指令发送失败或异常。

2. **媒体服务器绑定** — 每个 WVP 管理自己的 ZLM 节点。查询"负载最低的媒体服务器"时，只应该看到自己的 ZLM，不能把流调度到别的 WVP 的 ZLM 上（网络可能不通）。

3. **防止重复操作** — 云端录像、推流等操作，如果不按 `server_id` 过滤，所有实例都会对同一条记录执行操作，导致数据异常。

### 7.4 设备如何路由到对应的 WVP 实例

设备（摄像头）并不知道 `serverId` 的存在。设备的路由在**网络层面**完成，靠的是 SIP 服务器地址配置：

```
设备（摄像头）
  │
  │  摄像头出厂/部署时配置：
  │    SIP 服务器 IP: 192.168.1.100
  │    SIP 服务器端口: 5060
  │    SIP 域编号: 3402000000
  │    设备编号: 34020000001320000001
  │
  │  发送 SIP REGISTER → 192.168.1.100:5060
  │
  ▼
WVP 实例 A (192.168.1.100:5060)  ← 设备注册到这里
WVP 实例 B (192.168.1.100:5061)  ← 收不到这个设备的消息
```

设备只认 IP + 端口，SIP REGISTER 发到哪个 WVP，就归哪个 WVP 管。`serverId` 纯粹是 WVP 内部在 Redis 和 MySQL 中区分"这个设备是我的还是别的实例的"，设备完全不感知。

**SIP 端口分配规则：**
- **同一台机器**部署多个 WVP 实例 → SIP 端口必须不同（端口不能重复绑定）
- **不同机器**部署多个 WVP 实例 → SIP 端口可以相同（因为 IP 不同）

### 7.5 WVP 与 ZLM 的对应关系

正常部署下，每个 WVP 实例拥有一个独占的 ZLM 媒体服务器。

**为什么需要独占：**
- ZLM 负责接收 RTP 媒体流和提供播放端口，SSRC 池、RTP 端口、流注册等资源由 WVP 管理
- 流的管理和 SIP 信令是紧耦合的：WVP 通过 SIP INVITE 让摄像头向 ZLM 推流，WVP 必须精确控制 ZLM 的 RTP 端口和 SSRC 分配
- 如果两个 WVP 共用一个 ZLM，会出现 SSRC 冲突、端口冲突、流状态管理混乱等问题

**WVP 也支持一对多（负载均衡）：**
- 一个 WVP 实例可以管理多个 ZLM 节点
- WVP 会在多个 ZLM 之间做负载均衡，选择负载最低的节点接收新流
- 这是横向扩展流媒体处理能力的方式

**典型部署拓扑：**

```
租户 A：
  WVP-A ──── ZLM-A

租户 B：
  WVP-B ──── ZLM-B

扩容场景（单租户流媒体负载高）：
  WVP-A ─┬── ZLM-A-1
         └── ZLM-A-2
```

**数据库和 Redis 中的体现：**
- `wvp_media_server` 表通过 `server_id` 列标记 ZLM 属于哪个 WVP 实例
- Redis 中 `VMP_MEDIA_SERVER_INFO:{serverId}` 和 `VMP_ONLINE_MEDIA_SERVERS:{serverId}` 按 serverId 隔离
- `VMP_SSRC_INFO_{serverId}_{mediaServerId}` 同时包含 WVP 实例 ID 和 ZLM 节点 ID

### 7.6 多实例共享 Redis 的配置要点

**每个 WVP 实例必须满足：**

1. **`serverId` 唯一** — 在 `application-{profile}.yml` 的 `user-settings.server-id` 中配置，不能与其他实例重复
2. **Redis 连接配置一致** — 所有实例指向相同的 `host`、`port`、`database`
3. **媒体服务器绑定** — 每个 WVP 实例关联自己的 ZLM 节点（或共享 ZLM 集群）
4. **SIP 端口不冲突** — 同一机器上的多个实例使用不同的 SIP 端口

---

## 8. 关键源文件索引

| 文件 | 职责 |
|------|------|
| `common/VideoManagerConstants.java` | 所有 Redis Key 常量定义 |
| `conf/redis/RedisTemplateConfig.java` | RedisTemplate Bean 配置 |
| `conf/redis/RedisRpcConfig.java` | Redis RPC 机制 |
| `conf/redis/RedisMsgListenConfig.java` | Pub/Sub 监听器注册 |
| `storager/impl/RedisCatchStorageImpl.java` | Redis 存储主实现 |
| `storager/IRedisCatchStorage.java` | Redis 存储接口 |
| `utils/redis/RedisUtil.java` | SCAN 工具 |
| `gb28181/session/SSRCFactory.java` | SSRC 池管理 |
| `gb28181/session/SipInviteSessionManager.java` | SIP INVITE 会话 |
| `gb28181/session/CatalogDataManager.java` | 目录同步 |
| `gb28181/bean/SubscribeHolder.java` | SIP 订阅状态 |
| `gb28181/task/deviceStatus/DeviceStatusManager.java` | 设备过期管理 |
| `service/impl/SendRtpServerServiceImpl.java` | RTP 发送 + 端口分配 |
| `service/impl/RtpServerServiceImpl.java` | RTP 鉴权 |
| `service/impl/UserApiKeyServiceImpl.java` | @Cacheable API Key |
| `media/service/impl/MediaServerServiceImpl.java` | 媒体服务器发现/负载 |
| `jt1078/service/impl/jt1078ServiceImpl.java` | JT1078 位置队列 |
| `jt1078/service/impl/jt1078PlayServiceImpl.java` | JT1078 播放会话 |
| `conf/ftpServer/UserManager.java` | FTP 用户会话 |
| `conf/WVPTimerTask.java` | 定时心跳写入 Redis |
| `web/custom/service/SyServiceImpl.java` | 第三方集成 Redis 通道 |

---

## 9. 总结

### 多实例共享 Redis：代码层面无需改动

WVP 的 Redis 使用已经通过 `serverId` 实现了实例级隔离，同时通过 `deviceId`、`streamId`、`channelId` 等业务标识实现了数据级隔离。内置的 Redis Pub/Sub 和 RPC 机制天然支持跨实例通信。

**部署时只需要：**

| 步骤 | 操作 |
|------|------|
| 1 | 所有 WVP 实例配置相同的 Redis 地址和 database |
| 2 | 确保 `serverId` 在每个实例中唯一 |
| 3 | 每个实例关联正确的 ZLM 媒体服务器 |
| 4 | GB28181 协议层面确保同一设备只注册到一个 WVP 实例 |

### 如果是不同租户的 WVP 共用 Redis

此场景需要额外的 Key 命名空间隔离（如添加 `tenantId` 前缀），改动范围较大，涉及 `VideoManagerConstants.java` 中所有 Key 常量及相关读写逻辑。
