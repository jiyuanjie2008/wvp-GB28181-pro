# WVP-GB28181-Pro 前后端接口文档（GPT54H 整合版）

> 本文档以 `glm-wvp-frontend-backend-interface.md` 为主基础，吸收 `klm-polaris-wvp-api-interface.md` 的模块化与速查组织方式，并基于当前项目代码进行校验后整理。
>
> 目标：提供一份**更适合作为项目主文档**的接口总览，兼顾完整性、可追溯性与快速查阅体验。

## 文档发布信息

| 项目 | 说明 |
|------|------|
| 文档版本 | `v1.0` |
| 文档状态 | 发布版候选 |
| 适用项目 | `wvp-GB28181-pro` |
| 适用范围 | Web 前端主调用接口、后端 REST 接口、WebSocket、兼容接口、条件模块 |
| 基线来源 | `glm-wvp-frontend-backend-interface.md` + `klm-polaris-wvp-api-interface.md` + 当前代码实现 |
| 校验依据 | `src/main/java/**`、`web/src/api/*.js`、安全与 WebSocket 配置 |
| 更新时间 | `2026-04-23` |
| 维护原则 | 以后端 Controller 为准、以前端调用为辅、兼容/条件模块单独标注 |

---

## 目录

1. [文档发布信息](#文档发布信息)
2. [文档说明](#1-文档说明)
3. [接口约定与访问方式](#2-接口约定与访问方式)
4. [HTTP REST API 模块总览](#3-http-rest-api-模块总览)
5. [核心模块接口速查](#4-核心模块接口速查)
6. [兼容接口与条件模块](#5-兼容接口与条件模块)
7. [WebSocket 接口](#6-websocket-接口)
8. [SIP 与内部能力说明](#7-sip-与内部能力说明)
9. [前端 API 文件索引](#8-前端-api-文件索引)
10. [文档校验备注](#9-文档校验备注)

---

## 1. 文档说明

### 1.1 文档范围

本文档覆盖以下接口形态：

- Web 前端直接调用的 HTTP REST API
- 项目内实际存在的 WebSocket 接口
- 与第三方/兼容系统对接的隐藏或条件接口
- 前端通过 HTTP 间接触发的 SIP 相关能力说明

### 1.2 代码依据

主要依据以下代码位置整理：

- 后端控制器：`src/main/java/**`
- 前端 API：`web/src/api/*.js`
- WebSocket 配置：`src/main/java/com/genersoft/iot/vmp/conf/websocket/WebSocketConfig.java`
- 鉴权与登出配置：`src/main/java/com/genersoft/iot/vmp/conf/security/*`
- 在线文档配置：`src/main/java/com/genersoft/iot/vmp/conf/SpringDocConfig.java`
- 项目依赖：`pom.xml`

### 1.3 使用原则

- **代码优先**：接口是否存在，以控制器和安全配置为准。
- **前端映射辅助**：前端 `web/src/api/*.js` 用于补充调用关系，不单独作为接口存在性的唯一依据。
- **条件模块单独标注**：如 `jt1078`、`sy` 等模块只有在配置开启时才生效。
- **隐藏接口单独标注**：如 `/api/v1/**` 兼容接口属于实际存在但默认不作为主业务接口暴露的能力。

---

## 2. 接口约定与访问方式

### 2.1 基础访问信息

| 项目 | 说明 |
|------|------|
| 后端工程根 | `src/main/java` |
| 前端 API 根 | `web/src/api` |
| 在线文档入口 | `/doc.html` |
| OpenAPI 路径 | `/v3/api-docs/**` |

### 2.2 认证方式

| 项目 | 说明 |
|------|------|
| 登录接口 | `/api/user/login` |
| 认证 Header | `access-token` |
| Header 常量 | `JwtUtils.HEADER` |
| 登出接口 | `/api/user/logout` |
| 携带方式 | Header 或部分接口支持参数传递 |

说明：

- `UserController` 中 `login` 同时支持 `GET /api/user/login` 与 `POST /api/user/login`。
- 登出不是 `UserController` 方法，而是 Spring Security 在 `WebSecurityConfig` 中配置的 `logoutUrl("/api/user/logout")`。

### 2.3 在线文档能力

项目已集成：

- `springdoc-openapi-starter-webmvc-ui`
- `springdoc-openapi-starter-webmvc-api`
- `knife4j-openapi3-jakarta-spring-boot-starter`

因此默认可通过：

- `/doc.html`
- `/swagger-ui/**`

访问在线接口文档；是否启用受配置项控制。

### 2.4 接口鉴权补充

配置中存在：

- `interface-authentication`
- `interface-authentication-excludes`

默认排除项包含：

- `/api/v1/**`

因此 `/api/v1/**` 更偏向兼容/外部对接接口，不应与 Web 前端常规业务接口混为一谈。

---

## 3. HTTP REST API 模块总览

> 本表优先体现“模块 -> Controller -> 路径前缀 -> 前端文件 -> 备注”的快速索引关系。

| 模块 | 后端 Controller | 路径前缀 | 前端 API 文件 | 备注 |
|------|-----------------|----------|---------------|------|
| 用户管理 | `com.genersoft.iot.vmp.vmanager.user.UserController` | `/api/user` | `user.js` | 登录、用户信息、密码、用户管理 |
| 角色管理 | `com.genersoft.iot.vmp.vmanager.user.RoleController` | `/api/role` | `role.js` | 角色增删查 |
| 用户 API Key | `com.genersoft.iot.vmp.vmanager.user.UserApiKeyController` | `/api/userApiKey` | `userApiKey.js` | API Key 生命周期管理 |
| 服务管理 | `com.genersoft.iot.vmp.vmanager.server.ServerController` | `/api/server` | `server.js` | 服务状态、配置、地图配置、文档地址 |
| 日志管理 | `com.genersoft.iot.vmp.vmanager.log.LogController` | `/api/log` | `log.js` | 日志文件查询 |
| 国标设备查询 | `com.genersoft.iot.vmp.gb28181.controller.DeviceQuery` | `/api/device/query` | `device.js` | 设备、通道、订阅、统计、截图 |
| 国标设备控制 | `com.genersoft.iot.vmp.gb28181.controller.DeviceControl` | `/api/device/control` | `device.js` | 布防、撤防、录像、看守位、拉框控制 |
| 国标设备配置 | `com.genersoft.iot.vmp.gb28181.controller.DeviceConfig` | `/api/device/config` | `device.js` | 基础参数查询与设置 |
| 实时点播 | `com.genersoft.iot.vmp.gb28181.controller.PlayController` | `/api/play` | `play.js` | 直播开始、停止、广播 |
| 视频回放 | `com.genersoft.iot.vmp.gb28181.controller.PlaybackController` | `/api/playback` | `playback.js` | 回放开始、暂停、恢复、拖动、倍速 |
| 国标录像 | `com.genersoft.iot.vmp.gb28181.controller.GBRecordController` | `/api/gb_record` | `gbRecord.js` | 录像查询、下载、下载进度 |
| 前端设备控制 | `com.genersoft.iot.vmp.gb28181.controller.PtzController` | `/api/front-end` | `frontEnd.js` | PTZ、预置位、巡航、扫描、雨刷、辅助开关 |
| 全局通道管理 | `com.genersoft.iot.vmp.gb28181.controller.ChannelController` | `/api/common/channel` | `commonChannel.js` | 通道列表、挂载、播放、回放、地图抽稀 |
| 全局通道前端控制 | `com.genersoft.iot.vmp.gb28181.controller.ChannelFrontEndController` | `/api/common/channel/front-end` | `commonChannel.js` | 针对全局通道的 PTZ/预置位/扫描等 |
| 级联平台管理 | `com.genersoft.iot.vmp.gb28181.controller.PlatformController` | `/api/platform` | `platform.js` | 上级平台与共享通道管理 |
| 行政区划 | `com.genersoft.iot.vmp.gb28181.controller.RegionController` | `/api/region` | `region.js` | 行政区域查询与维护 |
| 业务分组 | `com.genersoft.iot.vmp.gb28181.controller.GroupController` | `/api/group` | `group.js` | 分组树及分组维护 |
| 移动位置 | `com.genersoft.iot.vmp.gb28181.controller.MobilePositionController` | `/api/position` | 无固定前端文件 | 历史轨迹、实时位置、订阅 |
| 媒体流相关 | `com.genersoft.iot.vmp.gb28181.controller.MediaController` | `/api/media` | 无固定前端文件 | 按 app/stream 获取流地址 |
| 拉流代理 | `com.genersoft.iot.vmp.streamProxy.controller.StreamProxyController` | `/api/proxy` | `streamProxy.js` | 代理流增删改查、启停 |
| 推流管理 | `com.genersoft.iot.vmp.streamPush.controller.StreamPushController` | `/api/push` | `streamPush.js` | 推流列表、增删改、导入、启停 |
| 云端录像 | `com.genersoft.iot.vmp.vmanager.cloudRecord.CloudRecordController` | `/api/cloud/record` | `cloudRecord.js` | 云端录像查询、下载、ZIP |
| 录制计划 | `com.genersoft.iot.vmp.vmanager.recordPlan.RecordPlanController` | `/api/record/plan` | `recordPlan.js` | 计划增删改查 |
| 报警管理 | `com.genersoft.iot.vmp.vmanager.alarm.AlarmController` | `/api/alarm` | `alarm.js` | 报警查询、清理、截图 |
| JT1078 部标终端/通道 | `com.genersoft.iot.vmp.jt1078.controller.JT1078TerminalController` | `/api/jt1078/terminal` | `jtDevice.js` | 条件模块，终端/通道维护 |
| JT1078 部标设备控制 | `com.genersoft.iot.vmp.jt1078.controller.JT1078Controller` | `/api/jt1078` | `jtDevice.js` | 条件模块，实时流、回放、控制、参数 |
| 第三方接口 | `com.genersoft.iot.vmp.web.custom.CameraChannelController` | `/api/sy` | `commonChannel.js`（仅测试残留） | 条件模块，对外兼容接口 |
| RTP 对接 | `com.genersoft.iot.vmp.vmanager.rtp.RtpController` | `/api/rtp` | 无固定前端文件 | 第三方 RTP 能力 |
| PS 对接 | `com.genersoft.iot.vmp.vmanager.ps.PsController` | `/api/ps` | 无固定前端文件 | 第三方 PS 能力 |
| 兼容接口 | `com.genersoft.iot.vmp.web.gb28181.ApiController` 等 | `/api/v1` | 无前端常规调用 | 隐藏接口，偏对外兼容 |
| 测试接口 | `com.genersoft.iot.vmp.vmanager.TestController` | `/api/test` | 无 | 开发/测试用途 |

---

## 4. 核心模块接口速查

> 这里保留项目最常用、最稳定、最适合人查阅的接口清单。需要追溯完整实现时，请以 Controller 为准。

### 4.1 用户、角色与 API Key

#### 用户管理：`/api/user`

| 方法 | 路径 | 前端函数 | 说明 |
|------|------|----------|------|
| GET / POST | `/api/user/login` | `user.login` | 登录，返回 `access-token` |
| GET | `/api/user/logout` | `user.logout` | 登出，由安全配置提供 |
| POST | `/api/user/userInfo` | `user.getUserInfo` | 获取当前用户信息 |
| POST | `/api/user/changePushKey` | `user.changePushKey` | 修改推送 Key |
| GET | `/api/user/users` | `user.queryList` | 分页查询用户 |
| POST | `/api/user/add` | `user.add` | 添加用户 |
| DELETE | `/api/user/delete` | `user.removeById` | 删除用户 |
| POST | `/api/user/changePassword` | `user.changePassword` | 修改本人密码 |
| POST | `/api/user/changePasswordForAdmin` | `user.changePasswordForAdmin` | 管理员修改用户密码 |

#### 角色管理：`/api/role`

| 方法 | 路径 | 前端函数 | 说明 |
|------|------|----------|------|
| POST | `/api/role/add` | 无 | 添加角色 |
| DELETE | `/api/role/delete` | 无 | 删除角色 |
| GET | `/api/role/all` | `role.getAll` | 查询所有角色 |

#### 用户 API Key：`/api/userApiKey`

| 方法 | 路径 | 前端函数 | 说明 |
|------|------|----------|------|
| POST | `/api/userApiKey/add` | `userApiKey.add` | 添加 API Key |
| GET | `/api/userApiKey/userApiKeys` | `userApiKey.queryList` | 分页查询 API Key |
| POST | `/api/userApiKey/enable` | `userApiKey.enable` | 启用 |
| POST | `/api/userApiKey/disable` | `userApiKey.disable` | 禁用 |
| POST | `/api/userApiKey/reset` | `userApiKey.reset` | 重置密钥 |
| POST | `/api/userApiKey/remark` | `userApiKey.remark` | 修改备注 |
| DELETE | `/api/userApiKey/delete` | `userApiKey.remove` | 删除 |

### 4.2 设备查询、控制与配置

#### 国标设备查询：`/api/device/query`

| 方法 | 路径 | 前端函数 | 说明 |
|------|------|----------|------|
| GET | `/api/device/query/devices` | `device.queryDevices` | 分页查询设备 |
| GET | `/api/device/query/devices/{deviceId}` | `device.queryDeviceOne` | 查询单个设备 |
| GET | `/api/device/query/devices/{deviceId}/channels` | `device.queryChannels` | 分页查询通道 |
| GET | `/api/device/query/devices/{deviceId}/sync` | `device.sync` | 同步设备通道 |
| GET | `/api/device/query/sync_status` | `device.queryDeviceSyncStatus` | 获取同步进度 |
| DELETE | `/api/device/query/devices/{deviceId}/delete` | `device.deleteDevice` | 删除设备 |
| GET | `/api/device/query/info` | 无 | 设备信息查询 |
| GET | `/api/device/query/alarm` | 无 | 设备报警查询 |
| GET | `/api/device/query/snap/{deviceId}/{channelId}` | 无 | 截图 |
| GET | `/api/device/query/subscribe/catalog` | `device.subscribeCatalog` | 目录订阅 |
| GET | `/api/device/query/subscribe/mobile-position` | `device.subscribeMobilePosition` | 位置订阅 |
| GET | `/api/device/query/subscribe/alarm` | `device.subscribeForAlarm` | 报警订阅 |

#### 国标设备控制：`/api/device/control`

| 方法 | 路径 | 前端函数 | 说明 |
|------|------|----------|------|
| GET | `/api/device/control/teleboot/{deviceId}` | 无 | 远程启动 |
| GET | `/api/device/control/record` | `device.deviceRecord` | 手动录像控制 |
| GET | `/api/device/control/guard` | `device.setGuard` / `device.resetGuard` | 布防/撤防 |
| GET | `/api/device/control/reset_alarm` | 无 | 报警复位 |
| GET | `/api/device/control/i_frame` | 无 | 强制关键帧 |
| GET | `/api/device/control/home_position` | 无 | 看守位控制 |
| GET | `/api/device/control/drag_zoom/zoom_in` | 无 | 拉框放大 |
| GET | `/api/device/control/drag_zoom/zoom_out` | 无 | 拉框缩小 |

#### 设备配置：`/api/device/config`

| 方法 | 路径 | 前端函数 | 说明 |
|------|------|----------|------|
| GET | `/api/device/config/query` | `device.queryBasicParam` | 查询设备基础参数 |
| GET | `/api/device/config/basicParam` | 无 | 设置设备基础参数 |

说明：当前前端 `device.queryBasicParam()` 使用的 URL 形态为 `/api/device/config/query/{deviceId}/BasicParam`，而后端 `DeviceConfig` 暴露的是 `/api/device/config/query` 并通过参数传入 `deviceId` 与 `configType`。该处存在前后端约定不完全一致，建议后续单独核对联调状态。

### 4.3 点播、回放与国标录像

#### 实时点播：`/api/play`

| 方法 | 路径 | 前端函数 | 说明 |
|------|------|----------|------|
| GET | `/api/play/start/{deviceId}/{channelId}` | `play.play` | 开始直播 |
| GET | `/api/play/stop/{deviceId}/{channelId}` | `play.stop` | 停止直播 |
| GET | `/api/play/broadcast/{deviceId}/{channelId}` | `play.broadcastStart` | 开始语音广播 |
| GET | `/api/play/broadcast/stop/{deviceId}/{channelId}` | `play.broadcastStop` | 停止语音广播 |

#### 视频回放：`/api/playback`

| 方法 | 路径 | 前端函数 | 说明 |
|------|------|----------|------|
| GET | `/api/playback/start/{deviceId}/{channelId}` | `playback.play` | 开始回放 |
| GET | `/api/playback/stop/{deviceId}/{channelId}/{streamId}` | `playback.stop` | 停止回放 |
| GET | `/api/playback/pause/{streamId}` | `playback.pause` | 暂停回放 |
| GET | `/api/playback/resume/{streamId}` | `playback.resume` | 恢复回放 |
| GET | `/api/playback/seek/{streamId}/{seekTime}` | 无 | 拖动定位 |
| GET | `/api/playback/speed/{streamId}/{speed}` | `playback.setSpeed` | 倍速播放 |

#### 国标录像：`/api/gb_record`

| 方法 | 路径 | 前端函数 | 说明 |
|------|------|----------|------|
| GET | `/api/gb_record/query/{deviceId}/{channelId}` | `gbRecord.query` | 查询录像 |
| GET | `/api/gb_record/download/start/{deviceId}/{channelId}` | `gbRecord.startDownLoad` | 开始下载 |
| GET | `/api/gb_record/download/stop/{deviceId}/{channelId}/{stream}` | `gbRecord.stopDownLoad` | 停止下载 |
| GET | `/api/gb_record/download/progress/{deviceId}/{channelId}/{stream}` | `gbRecord.queryDownloadProgress` | 查询下载进度 |

### 4.4 前端设备控制与全局通道

#### 前端设备控制：`/api/front-end`

| 方法 | 路径 | 前端函数 | 说明 |
|------|------|----------|------|
| GET | `/api/front-end/ptz/{deviceId}/{channelId}` | `frontEnd.ptz` | 云台控制 |
| GET | `/api/front-end/fi/iris/{deviceId}/{channelId}` | `frontEnd.iris` | 光圈控制 |
| GET | `/api/front-end/fi/focus/{deviceId}/{channelId}` | `frontEnd.focus` | 聚焦控制 |
| GET | `/api/front-end/preset/query/{deviceId}/{channelId}` | `frontEnd.queryPreset` | 查询预置位 |
| GET | `/api/front-end/preset/add/{deviceId}/{channelId}` | `frontEnd.addPreset` | 添加预置位 |
| GET | `/api/front-end/preset/call/{deviceId}/{channelId}` | `frontEnd.callPreset` | 调用预置位 |
| GET | `/api/front-end/preset/delete/{deviceId}/{channelId}` | `frontEnd.deletePreset` | 删除预置位 |
| GET | `/api/front-end/cruise/start/{deviceId}/{channelId}` | `frontEnd.startCruise` | 开始巡航 |
| GET | `/api/front-end/cruise/stop/{deviceId}/{channelId}` | `frontEnd.stopCruise` | 停止巡航 |
| GET | `/api/front-end/scan/start/{deviceId}/{channelId}` | `frontEnd.startScan` | 开始扫描 |
| GET | `/api/front-end/scan/stop/{deviceId}/{channelId}` | `frontEnd.stopScan` | 停止扫描 |
| GET | `/api/front-end/wiper/{deviceId}/{channelId}` | `frontEnd.wiper` | 雨刷 |
| GET | `/api/front-end/auxiliary/{deviceId}/{channelId}` | `frontEnd.auxiliary` | 辅助开关 |

#### 全局通道管理：`/api/common/channel`

| 方法 | 路径 | 前端函数 | 说明 |
|------|------|----------|------|
| GET | `/api/common/channel/one` | `commonChannel.queryOne` | 查询单个通道 |
| GET | `/api/common/channel/list` | `commonChannel.getList` | 获取通道列表 |
| POST | `/api/common/channel/update` | `commonChannel.update` | 更新通道 |
| POST | `/api/common/channel/reset` | `commonChannel.reset` | 重置通道字段 |
| POST | `/api/common/channel/add` | `commonChannel.add` | 新增通道 |
| POST | `/api/common/channel/region/add` | `commonChannel.addToRegion` | 加入行政区划 |
| POST | `/api/common/channel/group/add` | `commonChannel.addToGroup` | 加入业务分组 |
| GET | `/api/common/channel/play` | `commonChannel.playChannel` | 播放通道 |
| GET | `/api/common/channel/play/stop` | `commonChannel.stopPlayChannel` | 停止播放 |
| GET | `/api/common/channel/playback/query` | `commonChannel.queryRecord` | 查询录像 |
| GET | `/api/common/channel/playback` | `commonChannel.playback` | 回放通道 |
| GET | `/api/common/channel/map/list` | `commonChannel.getAllForMap` | 地图通道列表 |
| POST | `/api/common/channel/map/reset-level` | `commonChannel.resetLevel` | 重置抽稀层级 |
| POST | `/api/common/channel/map/thin/draw` | `commonChannel.drawThin` | 执行抽稀 |
| GET | `/api/common/channel/map/thin/save` | `commonChannel.saveThin` | 保存抽稀结果 |
| GET | `/api/common/channel/map/thin/progress` | `commonChannel.thinProgress` | 抽稀进度 |
| GET | `/api/common/channel/map/tile/{z}/{x}/{y}` | 无 | 标准 MVT 切片 |
| GET | `/api/common/channel/map/thin/tile/{z}/{x}/{y}` | 无 | 抽稀后 MVT 切片 |

#### 全局通道前端控制：`/api/common/channel/front-end`

| 方法 | 路径 | 前端函数 | 说明 |
|------|------|----------|------|
| GET | `/api/common/channel/front-end/ptz` | `commonChannel.ptz` | 云台控制 |
| GET | `/api/common/channel/front-end/fi/iris` | `commonChannel.iris` | 光圈控制 |
| GET | `/api/common/channel/front-end/fi/focus` | `commonChannel.focus` | 聚焦控制 |
| GET | `/api/common/channel/front-end/preset/query` | `commonChannel.queryPreset` | 查询预置位 |
| GET | `/api/common/channel/front-end/preset/add` | `commonChannel.addPreset` | 添加预置位 |
| GET | `/api/common/channel/front-end/preset/call` | `commonChannel.callPreset` | 调用预置位 |
| GET | `/api/common/channel/front-end/preset/delete` | `commonChannel.deletePreset` | 删除预置位 |
| GET | `/api/common/channel/front-end/tour/start` | `commonChannel.startCruise` | 开始巡航 |
| GET | `/api/common/channel/front-end/tour/stop` | `commonChannel.stopCruise` | 停止巡航 |
| GET | `/api/common/channel/front-end/scan/start` | `commonChannel.startScan` | 开始扫描 |
| GET | `/api/common/channel/front-end/scan/stop` | `commonChannel.stopScan` | 停止扫描 |
| GET | `/api/common/channel/front-end/wiper` | `commonChannel.wiper` | 雨刷 |
| GET | `/api/common/channel/front-end/auxiliary` | `commonChannel.auxiliary` | 辅助开关 |

### 4.5 平台、区域、分组

#### 级联平台：`/api/platform`

| 方法 | 路径 | 前端函数 | 说明 |
|------|------|----------|------|
| GET | `/api/platform/server_config` | `platform.getServerConfig` | 获取级联服务配置 |
| GET | `/api/platform/info/{id}` | 无 | 获取单个平台详情 |
| GET | `/api/platform/query` | `platform.query` | 分页查询级联平台 |
| POST | `/api/platform/add` | `platform.add` | 添加上级平台 |
| POST | `/api/platform/update` | `platform.update` | 更新上级平台 |
| DELETE | `/api/platform/delete` | `platform.remove` | 删除上级平台 |
| GET | `/api/platform/exit/{serverGBId}` | `platform.exit` | 查询上级平台是否已存在 |
| GET | `/api/platform/channel/list` | `platform.getChannelList` | 分页查询共享通道 |
| POST | `/api/platform/channel/add` | `platform.addChannel` | 向上级平台添加通道 |
| DELETE | `/api/platform/channel/remove` | `platform.removeChannel` | 从上级平台移除通道 |
| GET | `/api/platform/channel/push` | `platform.pushChannel` | 推送通道 |
| POST | `/api/platform/channel/device/add` | `platform.addChannelByDevice` | 按设备批量添加通道 |
| POST | `/api/platform/channel/device/remove` | `platform.removeChannelByDevice` | 按设备批量移除通道 |
| POST | `/api/platform/channel/custom/update` | `platform.updateCustomChannel` | 更新共享通道自定义信息 |

#### 行政区划：`/api/region`

| 方法 | 路径 | 前端函数 | 说明 |
|------|------|----------|------|
| POST | `/api/region/add` | `region.add` | 添加区域 |
| GET | `/api/region/page/list` | 无 | 分页查询区域 |
| GET | `/api/region/tree/list` | `region.getTreeList` | 查询区域树 |
| GET | `/api/region/tree/query` | `region.queryTree` | 条件搜索区域树节点 |
| POST | `/api/region/update` | `region.update` | 更新区域 |
| DELETE | `/api/region/delete` | `region.deleteRegion` | 删除区域 |
| GET | `/api/region/one` | 无 | 查询单个区域 |
| GET | `/api/region/base/child/list` | `region.queryChildListInBase` | 查询基础行政区划子节点 |
| GET | `/api/region/path` | `region.queryPath` | 查询区域层级路径 |
| GET | `/api/region/sync` | 无 | 从通道同步行政区划 |
| GET | `/api/region/description` | `region.description` | 根据编码查询描述 |
| GET | `/api/region/addByCivilCode` | `region.addByCivilCode` | 根据行政区划编码补充节点 |

#### 业务分组：`/api/group`

| 方法 | 路径 | 前端函数 | 说明 |
|------|------|----------|------|
| POST | `/api/group/add` | `group.add` | 添加分组 |
| GET | `/api/group/tree/list` | `group.getTreeList` | 查询分组树 |
| GET | `/api/group/tree/query` | `group.queryTree` | 条件搜索分组树节点 |
| POST | `/api/group/update` | `group.update` | 更新分组 |
| DELETE | `/api/group/delete` | `group.deleteGroup` | 删除分组 |
| GET | `/api/group/path` | `group.getPath` | 查询分组层级路径 |

### 4.6 媒体、位置、代理、推流

#### 移动位置：`/api/position`

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/position/history/{deviceId}` | 查询历史轨迹 |
| GET | `/api/position/latest/{deviceId}` | 查询设备最新位置 |
| GET | `/api/position/realtime/{deviceId}` | 通过 SIP 查询实时位置 |
| GET | `/api/position/subscribe/{deviceId}` | 订阅位置上报 |

#### 媒体流相关：`/api/media`

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/media/stream_info_by_app_and_stream` | 按 `app + stream` 获取播放信息 |
| GET | `/api/media/getPlayUrl` | 获取推流播放地址 |

#### 拉流代理：`/api/proxy`

| 方法 | 路径 | 前端函数 | 说明 |
|------|------|----------|------|
| GET | `/api/proxy/list` | `streamProxy.queryList` | 分页查询代理流 |
| GET | `/api/proxy/one` | 无 | 查询单条代理流 |
| POST | `/api/proxy/add` | `streamProxy.add` | 新增代理 |
| POST | `/api/proxy/update` | `streamProxy.update` | 更新代理 |
| GET | `/api/proxy/ffmpeg_cmd/list` | `streamProxy.queryFfmpegCmdList` | 获取 FFmpeg 模板 |
| DELETE | `/api/proxy/del` / `/api/proxy/delete` | `streamProxy.remove` | 删除代理 |
| GET | `/api/proxy/start` | `streamProxy.play` | 开始播放代理流 |
| GET | `/api/proxy/stop` | `streamProxy.stopPlay` | 停止播放代理流 |

#### 推流管理：`/api/push`

| 方法 | 路径 | 前端函数 | 说明 |
|------|------|----------|------|
| GET | `/api/push/list` | `streamPush.queryList` | 推流列表 |
| POST | `/api/push/add` | `streamPush.add` | 新增推流 |
| POST | `/api/push/update` | `streamPush.update` | 更新推流 |
| POST | `/api/push/remove` | `streamPush.remove` | 删除推流 |
| DELETE | `/api/push/batchRemove` | `streamPush.batchRemove` | 批量删除 |
| GET | `/api/push/start` | `streamPush.play` | 开始播放推流 |
| GET | `/api/push/forceClose` | 无 | 强制停止推流 |
| POST | `/api/push/upload` | 无 | Excel 导入 |

### 4.7 云端录像、录制计划、报警、日志、服务

#### 云端录像：`/api/cloud/record`

| 方法 | 路径 | 前端函数 | 说明 |
|------|------|----------|------|
| GET | `/api/cloud/record/date/list` | `cloudRecord.queryListByData` | 查询存在云端录像的日期 |
| GET | `/api/cloud/record/list` | `cloudRecord.queryList` | 分页查询云端录像 |
| GET | `/api/cloud/record/task/add` | `cloudRecord.addTask` | 添加合并任务 |
| GET | `/api/cloud/record/task/list` | `cloudRecord.queryTaskList` | 查询合并任务 |
| GET | `/api/cloud/record/collect/add` | 无 | 添加收藏 |
| GET | `/api/cloud/record/collect/delete` | 无 | 取消收藏 |
| GET | `/api/cloud/record/play/path` | `cloudRecord.getPlayPath` | 获取播放地址 |
| GET | `/api/cloud/record/loadRecord` | `cloudRecord.loadRecord` | 加载录像形成播放地址 |
| GET | `/api/cloud/record/seek` | `cloudRecord.seek` | 定位录像播放位置 |
| GET | `/api/cloud/record/speed` | `cloudRecord.speed` | 设置录像播放速度 |
| DELETE | `/api/cloud/record/delete` | `cloudRecord.deleteRecord` | 删除录像文件 |
| GET | `/api/cloud/record/download/zip` | 无 | 下载指定录像文件压缩包 |
| GET | `/api/cloud/record/zip` | 无 | 按条件打包下载录像 |
| GET | `/api/cloud/record/list-url` | 无 | 查询带播放/下载地址的录像列表 |

#### 录制计划：`/api/record/plan`

| 方法 | 路径 | 前端函数 | 说明 |
|------|------|----------|------|
| GET | `/api/record/plan/get` | `recordPlan.getPlan` | 查询单个计划 |
| POST | `/api/record/plan/add` | `recordPlan.add` | 新增计划 |
| POST | `/api/record/plan/update` | `recordPlan.update` | 更新计划 |
| DELETE | `/api/record/plan/delete` | `recordPlan.deletePlan` | 删除计划 |

#### 报警管理：`/api/alarm`

| 方法 | 路径 | 前端函数 | 说明 |
|------|------|----------|------|
| GET | `/api/alarm/list` | `alarm.getAlarmList` | 分页查询报警列表 |
| DELETE | `/api/alarm/delete` | `alarm.deleteAlarms` | 按 ID 删除报警 |
| DELETE | `/api/alarm/clear` | `alarm.clearAlarms` | 按筛选条件清空报警 |
| GET | `/api/alarm/snap/{id}` | 无 | 获取报警快照图片 |

#### 日志管理：`/api/log`

| 方法 | 路径 | 前端函数 | 说明 |
|------|------|----------|------|
| GET | `/api/log/list` | `log.queryList` | 分页查询日志文件 |
| GET | `/api/log/file/{fileName}` | 无 | 下载指定日志文件 |

#### 服务管理：`/api/server`

| 方法 | 路径 | 前端函数 | 说明 |
|------|------|----------|------|
| GET | `/api/server/media_server/list` | `server.getMediaServerList` | 流媒体服务列表 |
| GET | `/api/server/media_server/online/list` | `server.getOnlineMediaServerList` | 在线流媒体服务列表 |
| GET | `/api/server/media_server/one/{id}` | `server.getMediaServer` | 获取单个流媒体服务 |
| GET | `/api/server/media_server/check` | `server.checkMediaServer` | 校验流媒体服务连接 |
| GET | `/api/server/media_server/record/check` | `server.checkMediaServerRecord` | 校验录像管理服务 |
| POST | `/api/server/media_server/save` | `server.saveMediaServer` | 保存流媒体服务配置 |
| DELETE | `/api/server/media_server/delete` | `server.deleteMediaServer` | 删除流媒体服务 |
| GET | `/api/server/media_server/media_info` | `server.getMediaInfo` | 获取媒体流信息 |
| GET | `/api/server/system/configInfo` | `server.getSystemConfig` | 获取系统配置概要 |
| GET | `/api/server/version` | 无 | 获取版本信息 |
| GET | `/api/server/config` | 无 | 按类型获取配置 |
| GET | `/api/server/system/info` | `server.getSystemInfo` | 获取系统信息 |
| GET | `/api/server/media_server/load` | `server.getMediaServerLoad` | 获取媒体节点负载 |
| GET | `/api/server/resource/info` | `server.getResourceInfo` | 获取资源使用情况 |
| GET | `/api/server/info` | `server.info` | 获取系统概要信息与文档地址 |
| GET | `/api/server/map/config` | `server.getMapConfig` | 获取地图配置 |
| GET | `/api/server/map/model-icon/list` | `server.getModelList` | 获取地图模型图标列表 |
| GET | `/api/server/shutdown` | 无 | 关闭服务 |

---

## 5. 兼容接口与条件模块

### 5.1 `/api/v1/**` 兼容接口

对应后端类：

- `com.genersoft.iot.vmp.web.gb28181.ApiController`
- `com.genersoft.iot.vmp.web.gb28181.ApiDeviceController`
- `com.genersoft.iot.vmp.web.gb28181.ApiStreamController`
- `com.genersoft.iot.vmp.web.gb28181.ApiControlController`

特点：

- 标注了 `@Hidden`
- 默认不应作为 Web 前端主业务接口使用
- 更适合作为第三方系统兼容能力
- 默认在接口鉴权排除列表中可见 `/api/v1/**`

- 当前 Web 前端无常规页面主调用，以下接口表以“兼容能力速查”为主

#### `/api/v1` 系统兼容接口

| 方法 | 路径 | 前端函数 | 说明 |
|------|------|----------|------|
| GET | `/api/v1/getserverinfo` | 无 | 获取兼容系统服务信息 |
| GET | `/api/v1/userinfo` | 无 | 获取兼容系统用户信息（当前返回空） |
| GET | `/api/v1/login` | 无 | 兼容系统登录接口 |

#### `/api/v1/device` 兼容设备接口

| 方法 | 路径 | 前端函数 | 说明 |
|------|------|----------|------|
| GET | `/api/v1/device/list` | 无 | 兼容方式分页获取设备列表 |
| GET | `/api/v1/device/channellist` | 无 | 兼容方式获取设备通道列表 |
| GET | `/api/v1/device/fetchpreset` | 无 | 获取下级通道预置位 |

#### `/api/v1/stream` 兼容直播接口

| 方法 | 路径 | 前端函数 | 说明 |
|------|------|----------|------|
| GET | `/api/v1/stream/start` | 无 | 开始直播 |
| GET | `/api/v1/stream/stop` | 无 | 停止直播 |
| GET | `/api/v1/stream/touch` | 无 | 直播保活/触达 |

#### `/api/v1/control` 兼容控制接口

| 方法 | 路径 | 前端函数 | 说明 |
|------|------|----------|------|
| GET | `/api/v1/control/ptz` | 无 | 云台控制 |
| GET | `/api/v1/control/preset` | 无 | 预置位控制 |

### 5.2 `/api/sy/**` 第三方接口

对应类：

- `com.genersoft.iot.vmp.web.custom.CameraChannelController`

特点：

- 条件启用：`sy.enable=true`
- 路径前缀：`/api/sy`
- 适合第三方系统调用，不是当前 Web 前端主业务链路
- 前端 `commonChannel.js` 中仅存在一个测试函数 `test()` 指向 `/api/sy/camera/list/ids`，不应据此将该模块视为 Web 常规页面主调用链路

#### `/api/sy` 摄像机查询接口

| 方法 | 路径 | 前端函数 | 说明 |
|------|------|----------|------|
| GET | `/api/sy/camera/list` | 无 | 查询当前虚拟组织下摄像机列表 |
| GET | `/api/sy/camera/list-with-child` | 无 | 查询当前虚拟组织及其子节点摄像机 |
| GET | `/api/sy/camera/cont-with-child` | 无 | 查询摄像机总数和在线数 |
| GET | `/api/sy/camera/one` | 无 | 查询单个摄像头信息 |
| GET | `/api/sy/camera/update` | 无 | 更新摄像头信息 |
| POST | `/api/sy/camera/list/ids` | `commonChannel.test`（测试残留） | 根据编号查询多个摄像头 |
| GET | `/api/sy/camera/list/box` | 无 | 根据矩形范围查询摄像头 |
| POST | `/api/sy/camera/list/polygon` | 无 | 根据多边形查询摄像头 |
| GET | `/api/sy/camera/list/circle` | 无 | 根据圆形范围查询摄像头 |
| GET | `/api/sy/camera/list/address` | 无 | 根据安装地址和监视方位查询摄像头 |
| GET | `/api/sy/camera/list-for-mobile` | 无 | 查询移动端摄像机列表 |

#### `/api/sy` 播放与控制接口

| 方法 | 路径 | 前端函数 | 说明 |
|------|------|----------|------|
| GET | `/api/sy/camera/control/play` | 无 | 播放摄像头 |
| GET | `/api/sy/camera/control/stop` | 无 | 停止播放摄像头 |
| GET | `/api/sy/camera/control/ptz` | 无 | 云台控制 |
| GET | `/api/sy/push/play` | 无 | 获取推流播放地址（鉴权） |
| GET | `/api/sy/push/play-without-check` | 无 | 获取推流播放地址（不做检查） |

#### `/api/sy` 录像与收藏接口

| 方法 | 路径 | 前端函数 | 说明 |
|------|------|----------|------|
| GET | `/api/sy/record/collect/add` | 无 | 添加录像收藏 |
| GET | `/api/sy/record/collect/delete` | 无 | 移除录像收藏 |
| GET | `/api/sy/record/zip` | 无 | 下载录像压缩包 |
| GET | `/api/sy/record/list-url` | 无 | 查询带播放/下载地址的录像列表 |

### 5.3 `/api/rtp` 与 `/api/ps`

对应类：

- `com.genersoft.iot.vmp.vmanager.rtp.RtpController`
- `com.genersoft.iot.vmp.vmanager.ps.PsController`

说明：

- 面向第三方 RTP / PS 服务对接
- 不属于当前前端常规页面主调用集合
- 适合单独作为外部对接章节维护

### 5.4 `/api/jt1078/**` 条件模块

对应类：

- `com.genersoft.iot.vmp.jt1078.controller.JT1078TerminalController`
- `com.genersoft.iot.vmp.jt1078.controller.JT1078Controller`

启用条件：

- `jt1078.enable=true`

前端文件：

- `web/src/api/jtDevice.js`

#### `/api/jt1078/terminal` 终端与通道管理

| 方法 | 路径 | 前端函数 | 说明 |
|------|------|----------|------|
| GET | `/api/jt1078/terminal/list` | `jtDevice.queryDevices` | 分页查询部标终端 |
| GET | `/api/jt1078/terminal/query` | `jtDevice.queryDeviceById` | 查询单个终端 |
| POST | `/api/jt1078/terminal/update` | `jtDevice.update` | 更新终端 |
| POST | `/api/jt1078/terminal/add` | `jtDevice.add` | 新增终端 |
| DELETE | `/api/jt1078/terminal/delete` | `jtDevice.deleteDevice` | 删除终端 |
| GET | `/api/jt1078/terminal/channel/list` | `jtDevice.queryChannels` | 分页查询终端通道 |
| GET | `/api/jt1078/terminal/channel/one` | 无 | 查询单个通道 |
| POST | `/api/jt1078/terminal/channel/update` | `jtDevice.updateChannel` | 更新通道 |
| POST | `/api/jt1078/terminal/channel/add` | `jtDevice.addChannel` | 新增通道 |
| DELETE | `/api/jt1078/terminal/channel/delete` | 无 | 删除通道 |

#### `/api/jt1078` 实时点播与对讲

| 方法 | 路径 | 前端函数 | 说明 |
|------|------|----------|------|
| GET | `/api/jt1078/live/start` | `jtDevice.play` | 开始点播 |
| GET | `/api/jt1078/live/stop` | `jtDevice.stopPlay` | 停止点播 |
| GET | `/api/jt1078/live/pause` | 无 | 暂停点播 |
| GET | `/api/jt1078/live/continue` | 无 | 继续点播 |
| GET | `/api/jt1078/live/switch` | 无 | 切换码流类型 |
| GET | `/api/jt1078/talk/start` | `jtDevice.startTalk` | 开始语音对讲 |
| GET | `/api/jt1078/talk/stop` | `jtDevice.stopTalk` | 停止语音对讲 |

#### `/api/jt1078` 回放与录像下载

| 方法 | 路径 | 前端函数 | 说明 |
|------|------|----------|------|
| GET | `/api/jt1078/record/list` | `jtDevice.queryRecordList` | 查询录像资源列表 |
| GET | `/api/jt1078/playback/start` | `jtDevice.startPlayback` | 开始录像回放 |
| GET | `/api/jt1078/playback/control` | `jtDevice.controlPlayback` | 回放控制 |
| GET | `/api/jt1078/playback/stop` | `jtDevice.stopPlayback` | 停止回放 |
| GET | `/api/jt1078/playback/downloadUrl` | `jtDevice.getRecordTempUrl` | 获取录像下载地址 |
| GET | `/api/jt1078/playback/download` | 无 | 下载录像 |

#### `/api/jt1078` 设备控制与参数

| 方法 | 路径 | 前端函数 | 说明 |
|------|------|----------|------|
| GET | `/api/jt1078/ptz` | `jtDevice.ptz` | 云台控制 |
| GET | `/api/jt1078/fill-light` | `jtDevice.fillLight` | 补光灯控制 |
| GET | `/api/jt1078/wiper` | `jtDevice.wiper` | 雨刷控制 |
| GET | `/api/jt1078/config/get` | `jtDevice.queryConfig` | 查询终端参数 |
| POST | `/api/jt1078/config/set` | `jtDevice.setConfig` | 设置终端参数 |
| GET | `/api/jt1078/attribute` | `jtDevice.queryAttribute` | 查询终端属性 |
| GET | `/api/jt1078/position-info` | `jtDevice.queryPosition` | 查询位置信息 |
| GET | `/api/jt1078/link-detection` | `jtDevice.linkDetection` | 链路检测 |
| POST | `/api/jt1078/text-msg` | `jtDevice.sendTextMessage` | 文本信息下发 |
| GET | `/api/jt1078/telephone-callback` | `jtDevice.telephoneCallback` | 电话回拨 |
| GET | `/api/jt1078/driver-information` | `jtDevice.queryDriverInfo` | 查询驾驶员身份信息 |
| POST | `/api/jt1078/control/reset` | `jtDevice.reset` | 终端复位 |
| POST | `/api/jt1078/control/factory-reset` | `jtDevice.factoryReset` | 恢复出厂设置 |
| POST | `/api/jt1078/control/connection` | `jtDevice.connection` | 连接指定服务器 |
| GET | `/api/jt1078/control/door` | `jtDevice.controlDoor` | 车门控制 |
| GET | `/api/jt1078/control/temp-position-tracking` | 无 | 临时位置跟踪控制 |
| POST | `/api/jt1078/confirmation-alarm-message` | 无 | 人工确认报警消息 |

#### `/api/jt1078` 媒体与录音扩展接口

| 方法 | 路径 | 前端函数 | 说明 |
|------|------|----------|------|
| GET | `/api/jt1078/media/attribute` | `jtDevice.queryMediaAttribute` | 查询终端音视频属性 |
| POST | `/api/jt1078/media/list` | `jtDevice.queryMediaData` | 存储多媒体数据检索 |
| GET | `/api/jt1078/media/upload/one/upload` | 无 | 单条多媒体数据上传 |
| GET | `/api/jt1078/media/upload/one/delete` | 无 | 单条多媒体数据删除 |
| POST | `/api/jt1078/set-phone-book` | `jtDevice.setPhoneBook` | 设置电话本 |
| POST | `/api/jt1078/shooting` | `jtDevice.shooting` | 摄像头立即拍摄 |
| GET | `/api/jt1078/snap` | 无 | 抓图 |
| GET | `/api/jt1078/record/start` | 无 | 开始录音 |
| GET | `/api/jt1078/record/stop` | 无 | 停止录音 |

#### `/api/jt1078` 区域与路线扩展接口

| 方法 | 路径 | 前端函数 | 说明 |
|------|------|----------|------|
| POST | `/api/jt1078/area/circle/update` | 无 | 更新圆形区域 |
| POST | `/api/jt1078/area/circle/add` | 无 | 追加圆形区域 |
| POST | `/api/jt1078/area/circle/edit` | 无 | 修改圆形区域 |
| GET | `/api/jt1078/area/circle/delete` | 无 | 删除圆形区域 |
| GET | `/api/jt1078/area/circle/query` | 无 | 查询圆形区域 |
| POST | `/api/jt1078/area/rectangle/update` | 无 | 更新矩形区域 |
| POST | `/api/jt1078/area/rectangle/add` | 无 | 追加矩形区域 |
| POST | `/api/jt1078/area/rectangle/edit` | 无 | 修改矩形区域 |
| GET | `/api/jt1078/area/rectangle/delete` | 无 | 删除矩形区域 |
| GET | `/api/jt1078/area/rectangle/query` | 无 | 查询矩形区域 |
| POST | `/api/jt1078/area/polygon/set` | 无 | 设置多边形区域 |
| GET | `/api/jt1078/area/polygon/delete` | 无 | 删除多边形区域 |
| GET | `/api/jt1078/area/polygon/query` | 无 | 查询多边形区域 |
| POST | `/api/jt1078/route/set` | 无 | 设置路线 |
| GET | `/api/jt1078/route/delete` | 无 | 删除路线 |
| GET | `/api/jt1078/route/query` | 无 | 查询路线 |

### 5.5 `/api/test`

对应类：

- `com.genersoft.iot.vmp.vmanager.TestController`

说明：

- 开发/调试用途
- 不建议纳入正式对外接口基线

---

## 6. WebSocket 接口

### 6.1 日志通道

| 项目 | 说明 |
|------|------|
| WebSocket 路径 | `/channel/log` |
| 端点类 | `com.genersoft.iot.vmp.conf.webLog.LogChannel` |
| 注册配置 | `com.genersoft.iot.vmp.conf.websocket.WebSocketConfig` |
| 作用 | 提供日志消息实时通道 |

说明：

- `WebSocketConfig` 中通过 `ServerEndpointExporter` 显式注册了 `LogChannel.class`。
- 该能力属于项目真实存在的 WebSocket 接口，应单独列出，不应遗漏。

---

## 7. SIP 与内部能力说明

### 7.1 SIP 的定位

SIP 不是 Web 前端直接调用的接口层，而是后端与 GB28181 设备之间的信令通道。前端通常通过以下 HTTP 接口间接触发 SIP 操作：

- `/api/play/**`
- `/api/playback/**`
- `/api/gb_record/**`
- `/api/front-end/**`
- `/api/common/channel/front-end/**`
- `/api/position/realtime/**`
- `/api/device/query/subscribe/**`

### 7.2 内部媒体服务能力

项目中与媒体服务、ZLMediaKit、流转发相关的内部协作能力较多，但本次整理的主文档以**可直接调用的 HTTP / WebSocket 接口**为主。

因此：

- 与媒体节点状态联动、hook 订阅、事件处理相关的内部实现不在本清单中展开为“对外 API”。
- 若后续需要对接媒体节点内部回调，建议单独维护“媒体服务内部回调文档”。

---

## 8. 前端 API 文件索引

| 前端文件 | 对应模块 |
|---------|---------|
| `user.js` | 用户管理、登录、登出、密码、用户列表 |
| `role.js` | 角色管理 |
| `userApiKey.js` | 用户 API Key |
| `server.js` | 服务管理、地图配置 |
| `log.js` | 系统日志 |
| `device.js` | 国标设备查询、设备控制、设备配置、订阅 |
| `play.js` | 实时点播、广播 |
| `playback.js` | 回放控制 |
| `gbRecord.js` | 国标录像 |
| `frontEnd.js` | 基于设备/通道的前端控制 |
| `commonChannel.js` | 全局通道、全局通道前端控制、地图抽稀、回放 |
| `platform.js` | 级联平台 |
| `region.js` | 行政区划 |
| `group.js` | 业务分组 |
| `streamProxy.js` | 拉流代理 |
| `streamPush.js` | 推流管理 |
| `cloudRecord.js` | 云端录像 |
| `recordPlan.js` | 录制计划 |
| `alarm.js` | 报警管理 |
| `jtDevice.js` | JT1078 终端/通道/控制/回放 |
| `table.js` | 表格辅助能力，不是主业务接口模块 |

---

## 9. 文档校验备注

### 9.1 本次整合中已修正的典型问题

- **修正了全局通道前端控制的归属**  
  `/api/common/channel/front-end/**` 的真实控制器是 `ChannelFrontEndController`，不是 `ChannelController`。

- **剔除了后端未实现的错误接口项**  
  前端 `commonChannel.js` 中存在 `saveLevel()` 调用 `/api/common/channel/map/save-level`，但当前后端 `ChannelController` 中**没有该接口**，因此本文档不将其纳入正式接口清单。

- **保留了设备配置链路中的前后端差异说明**  
  前端 `device.queryBasicParam()` 当前使用的 URL 形态与后端 `DeviceConfig` 的标准签名并不完全一致，因此本文档按“后端真实接口”记录，并在设备配置章节显式标注该差异。

- **明确区分了前端主链路与兼容/条件模块**  
  `/api/v1/**`、`/api/sy/**`、`/api/rtp`、`/api/ps`、`/api/jt1078/**` 均单独标注，避免与常规 Web 页面接口混写导致误导。

### 9.2 建议的后续维护方式

建议以后按以下规则维护接口文档：

- 主文档保留“模块总览 + 核心接口速查”结构
- 第三方兼容接口单独拆分章节维护
- 条件模块显式写明启用配置
- 所有接口存在性以 Controller 为准
- 前端文件只用于补充“谁在调用”，不作为接口存在性的唯一依据

### 9.3 主文档维护规则

为保证本文档可持续作为主接口文档使用，建议固定遵循以下规则：

- **新增接口时同步更新**：凡新增 Controller 路径、前端 API 封装、WebSocket 端点、兼容接口，需同步更新本文档。
- **删除接口时同步移除**：接口废弃、隐藏或迁移后，应在本文档中同步删除或标记为历史能力。
- **模块归属保持单一来源**：接口归属以 `@RequestMapping` 所在 Controller 为准，不按页面位置或调用习惯主观归类。
- **条件模块必须写明启用开关**：如 `jt1078.enable`、`sy.enable`，必须在文档中显式标注，不与常规主链路混排。
- **兼容接口与主链路分离维护**：`/api/v1/**`、`/api/sy/**` 等兼容接口可保留在主文档索引中，但应长期保持独立分组。
- **明显不一致项必须留痕**：若存在“前端已调用但后端无实现”或“前后端路径约定不一致”的情况，应在文档校验备注中明确记录。

### 9.4 建议的文档更新时间触发条件

出现以下任一情况时，建议更新本文档：

- **接口路径变更**：新增、删除、重命名 REST 路径或 WebSocket 路径
- **鉴权规则变更**：登录、登出、Token Header、接口排除项发生变化
- **前端 API 封装变更**：`web/src/api/*.js` 新增模块或大规模重构
- **兼容模块变更**：`/api/v1/**`、`/api/sy/**`、`/api/jt1078/**` 增删接口或启用条件调整
- **媒体联动边界调整**：播放、回放、录像、下载、流代理等链路的对外接口发生变化

---

## 附：推荐阅读顺序

如果你是：

- **前端开发**：优先看第 3、4、8 章
- **后端开发**：优先看第 3、4、5、7 章
- **第三方对接方**：优先看第 2、5、6 章
- **项目维护者**：通读全文，并以第 9 章作为后续维护基线
