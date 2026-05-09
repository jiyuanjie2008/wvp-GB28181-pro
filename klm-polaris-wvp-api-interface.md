# Polaris-WVP 与 Web 前端接口文档

> 本文档整理 polaris-wvp（WVP-GB28181-Pro）后端服务与 Web 前端之间的所有接口，包括 HTTP REST API、WebSocket 以及 SIP 协议相关说明。
> 
> 生成日期: 2026-04-23

---

## 目录

1. [接口概述](#1-接口概述)
2. [HTTP REST API 接口](#2-http-rest-api-接口)
   - 2.1 [用户管理](#21-用户管理)
   - 2.2 [国标设备查询](#22-国标设备查询)
   - 2.3 [国标设备控制](#23-国标设备控制)
   - 2.4 [实时点播](#24-实时点播)
   - 2.5 [视频回放](#25-视频回放)
   - 2.6 [国标录像](#26-国标录像)
   - 2.7 [前端设备控制（PTZ/云台）](#27-前端设备控制)
   - 2.8 [全局通道管理](#28-全局通道管理)
   - 2.9 [级联平台管理](#29-级联平台管理)
   - 2.10 [行政区划](#210-行政区划)
   - 2.11 [分组管理](#211-分组管理)
   - 2.12 [报警管理](#212-报警管理)
   - 2.13 [云端录像](#213-云端录像)
   - 2.14 [录制计划](#214-录制计划)
   - 2.15 [拉流代理](#215-拉流代理)
   - 2.16 [推流管理](#216-推流管理)
   - 2.17 [服务管理](#217-服务管理)
   - 2.18 [部标设备（JT1078）](#218-部标设备-jt1078)
   - 2.19 [日志管理](#219-日志管理)
   - 2.20 [角色管理](#220-角色管理)
   - 2.21 [用户 API Key](#221-用户-api-key)
3. [WebSocket 接口](#3-websocket-接口)
4. [SIP 协议说明](#4-sip-协议说明)
5. [认证方式](#5-认证方式)

---

## 1. 接口概述

| 项目 | 说明 |
|------|------|
| 后端服务 | polaris-wvp (WVP-GB28181-Pro) |
| 前端框架 | Vue.js (vue-admin-template) |
| HTTP 端口 | `18978`（默认，可通过 `server.port` 配置） |
| API 基础路径 | `/api/*` |
| 接口文档 | 启动后访问 `http://<host>:<port>/doc.html` (Swagger/Knife4j) |
| 认证方式 | JWT Token (`access-token` Header) |

---

## 2. HTTP REST API 接口

### 2.1 用户管理

Controller: `com.genersoft.iot.vmp.vmanager.user.UserController`

| 方法 | 接口路径 | 前端 API 文件 | 说明 |
|------|---------|-------------|------|
| GET/POST | `/api/user/login` | `user.js` | 登录，返回 JWT Token |
| GET | `/api/user/logout` | `user.js` | 登出 |
| POST | `/api/user/userInfo` | `user.js` | 获取当前登录用户信息 |
| POST | `/api/user/changePassword` | `user.js` | 修改密码 |
| POST | `/api/user/add` | `user.js` | 添加用户（需管理员权限） |
| GET | `/api/user/users` | `user.js` | 分页查询用户 |
| DELETE | `/api/user/delete` | `user.js` | 删除用户 |
| POST | `/api/user/changePushKey` | `user.js` | 修改用户 pushKey |
| POST | `/api/user/changePasswordForAdmin` | `user.js` | 管理员修改用户密码 |
| GET | `/api/user/all` | - | 查询所有用户 |

---

### 2.2 国标设备查询

Controller: `com.genersoft.iot.vmp.gb28181.controller.DeviceQuery`

| 方法 | 接口路径 | 前端 API 文件 | 说明 |
|------|---------|-------------|------|
| GET | `/api/device/query/devices/{deviceId}` | `device.js` | 查询单个设备 |
| GET | `/api/device/query/devices` | `device.js` | 分页查询设备列表 |
| GET | `/api/device/query/devices/{deviceId}/channels` | `device.js` | 分页查询设备通道 |
| GET | `/api/device/query/devices/{deviceId}/sync` | `device.js` | 同步设备通道 |
| GET | `/api/device/query/streams` | `device.js` | 查询存在流的通道 |
| GET | `/api/device/query/sub_channels/{deviceId}/{parentChannelId}/channels` | `device.js` | 查询子通道 |
| GET | `/api/device/query/tree/{deviceId}` | `device.js` | 查询设备树 |
| GET | `/api/device/query/tree/channel/{deviceId}` | `device.js` | 查询通道树 |
| GET | `/api/device/query/channel/one` | `device.js` | 查询单个通道 |
| POST | `/api/device/query/transport/{deviceId}/{streamMode}` | `device.js` | 修改设备流传输模式 |
| GET | `/api/device/query/subscribe/catalog` | `device.js` | 订阅目录 |
| GET | `/api/device/query/subscribe/mobile-position` | `device.js` | 订阅移动位置 |
| GET | `/api/device/query/subscribe/alarm` | `device.js` | 订阅报警 |
| GET | `/api/device/query/sync_status` | `device.js` | 查询同步状态 |
| GET | `/api/device/config/query/{deviceId}/BasicParam` | `device.js` | 查询设备基本参数 |
| POST | `/api/device/query/channel/audio` | `device.js` | 修改通道音频状态 |
| POST | `/api/device/query/channel/stream/identification/update/` | `device.js` | 更新通道流标识 |
| POST | `/api/device/query/device/update` | `device.js` | 更新设备信息 |
| POST | `/api/device/query/device/add` | `device.js` | 添加设备 |
| GET | `/api/device/query/statistics/keepalive` | `device.js` | 查询心跳统计 |
| GET | `/api/device/query/statistics/register` | `device.js` | 查询注册统计 |

---

### 2.3 国标设备控制

Controller: `com.genersoft.iot.vmp.gb28181.controller.DeviceControl`

| 方法 | 接口路径 | 前端 API 文件 | 说明 |
|------|---------|-------------|------|
| GET | `/api/device/control/teleboot/{deviceId}` | - | 远程启动设备 |
| GET | `/api/device/control/record` | `device.js` | 录像控制（开始/停止） |
| GET | `/api/device/control/guard` | `device.js` | 布防/撤防 |
| GET | `/api/device/control/reset_alarm` | - | 报警复位 |
| GET | `/api/device/control/i_frame` | - | 强制关键帧 |
| GET | `/api/device/control/home_position` | - | 看守位控制 |

---

### 2.4 实时点播

Controller: `com.genersoft.iot.vmp.gb28181.controller.PlayController`

| 方法 | 接口路径 | 前端 API 文件 | 说明 |
|------|---------|-------------|------|
| GET | `/api/play/start/{deviceId}/{channelId}` | `play.js` | 开始点播 |
| GET | `/api/play/stop/{deviceId}/{channelId}` | `play.js` | 停止点播 |
| GET/POST | `/api/play/broadcast/{deviceId}/{channelId}` | `play.js` | 语音广播开始 |
| GET/POST | `/api/play/broadcast/stop/{deviceId}/{channelId}` | `play.js` | 停止语音广播 |
| POST | `/api/play/convertStop/{key}` | - | 结束转码 |
| GET | `/api/play/ssrc` | - | 获取所有 SSRC |
| GET | `/api/play/snap` | - | 获取截图 |

---

### 2.5 视频回放

Controller: `com.genersoft.iot.vmp.gb28181.controller.PlaybackController`

| 方法 | 接口路径 | 前端 API 文件 | 说明 |
|------|---------|-------------|------|
| GET | `/api/playback/start/{deviceId}/{channelId}` | `playback.js` | 开始回放 |
| GET | `/api/playback/stop/{deviceId}/{channelId}/{stream}` | `playback.js` | 停止回放 |
| GET | `/api/playback/pause/{streamId}` | `playback.js` | 回放暂停 |
| GET | `/api/playback/resume/{streamId}` | `playback.js` | 回放恢复 |
| GET | `/api/playback/seek/{streamId}/{seekTime}` | `playback.js` | 回放拖动 |
| GET | `/api/playback/speed/{streamId}/{speed}` | `playback.js` | 回放倍速 |

---

### 2.6 国标录像

Controller: `com.genersoft.iot.vmp.gb28181.controller.GBRecordController`

| 方法 | 接口路径 | 前端 API 文件 | 说明 |
|------|---------|-------------|------|
| GET | `/api/gb_record/query/{deviceId}/{channelId}` | `gbRecord.js` | 录像查询 |
| GET | `/api/gb_record/download/start/{deviceId}/{channelId}` | `gbRecord.js` | 开始历史媒体下载 |
| GET | `/api/gb_record/download/stop/{deviceId}/{channelId}/{streamId}` | `gbRecord.js` | 停止下载 |
| GET | `/api/gb_record/download/progress/{deviceId}/{channelId}/{streamId}` | `gbRecord.js` | 下载进度查询 |

---

### 2.7 前端设备控制

Controller: `com.genersoft.iot.vmp.gb28181.controller.PtzController`

| 方法 | 接口路径 | 前端 API 文件 | 说明 |
|------|---------|-------------|------|
| GET | `/api/front-end/common/{deviceId}/{channelId}` | - | 通用前端控制命令 |
| GET | `/api/front-end/ptz/{deviceId}/{channelId}` | `frontEnd.js` | 云台控制（方向/缩放） |
| GET | `/api/front-end/fi/iris/{deviceId}/{channelId}` | `frontEnd.js` | 光圈控制 |
| GET | `/api/front-end/fi/focus/{deviceId}/{channelId}` | `frontEnd.js` | 聚焦控制 |
| GET | `/api/front-end/preset/query/{deviceId}/{channelId}` | `frontEnd.js` | 查询预置位 |
| GET | `/api/front-end/preset/add/{deviceId}/{channelId}` | `frontEnd.js` | 设置预置位 |
| GET | `/api/front-end/preset/call/{deviceId}/{channelId}` | `frontEnd.js` | 调用预置位 |
| GET | `/api/front-end/preset/delete/{deviceId}/{channelId}` | `frontEnd.js` | 删除预置位 |
| GET | `/api/front-end/cruise/point/add/{deviceId}/{channelId}` | `frontEnd.js` | 巡航加入点 |
| GET | `/api/front-end/cruise/point/delete/{deviceId}/{channelId}` | `frontEnd.js` | 巡航删除点 |
| GET | `/api/front-end/cruise/speed/{deviceId}/{channelId}` | `frontEnd.js` | 设置巡航速度 |
| GET | `/api/front-end/cruise/time/{deviceId}/{channelId}` | `frontEnd.js` | 设置巡航停留时间 |
| GET | `/api/front-end/cruise/start/{deviceId}/{channelId}` | `frontEnd.js` | 开始巡航 |
| GET | `/api/front-end/cruise/stop/{deviceId}/{channelId}` | `frontEnd.js` | 停止巡航 |
| GET | `/api/front-end/scan/start/{deviceId}/{channelId}` | `frontEnd.js` | 开始扫描 |
| GET | `/api/front-end/scan/stop/{deviceId}/{channelId}` | `frontEnd.js` | 停止扫描 |
| GET | `/api/front-end/scan/set/left/{deviceId}/{channelId}` | `frontEnd.js` | 设置扫描左边界 |
| GET | `/api/front-end/scan/set/right/{deviceId}/{channelId}` | `frontEnd.js` | 设置扫描右边界 |
| GET | `/api/front-end/scan/set/speed/{deviceId}/{channelId}` | `frontEnd.js` | 设置扫描速度 |
| GET | `/api/front-end/wiper/{deviceId}/{channelId}` | `frontEnd.js` | 雨刷控制 |
| GET | `/api/front-end/auxiliary/{deviceId}/{channelId}` | `frontEnd.js` | 辅助开关控制 |

> 另在 `commonChannel.js` 中也有通用通道的前端控制接口（见 2.8）。

---

### 2.8 全局通道管理

Controller: `com.genersoft.iot.vmp.gb28181.controller.ChannelController`

| 方法 | 接口路径 | 前端 API 文件 | 说明 |
|------|---------|-------------|------|
| GET | `/api/common/channel/one` | `commonChannel.js` | 查询通道信息 |
| GET | `/api/common/channel/industry/list` | `commonChannel.js` | 行业编码列表 |
| GET | `/api/common/channel/type/list` | `commonChannel.js` | 设备类型列表 |
| GET | `/api/common/channel/network/identification/list` | `commonChannel.js` | 网络标识列表 |
| POST | `/api/common/channel/update` | `commonChannel.js` | 更新通道 |
| POST | `/api/common/channel/reset` | `commonChannel.js` | 重置通道 |
| POST | `/api/common/channel/add` | `commonChannel.js` | 添加通道 |
| GET | `/api/common/channel/list` | `commonChannel.js` | 通道列表 |
| GET | `/api/common/channel/civilcode/list` | `commonChannel.js` | 按行政区划查询 |
| GET | `/api/common/channel/civilCode/unusual/list` | `commonChannel.js` | 异常行政区划通道 |
| GET | `/api/common/channel/parent/unusual/list` | `commonChannel.js` | 异常父节点通道 |
| POST | `/api/common/channel/civilCode/unusual/clear` | `commonChannel.js` | 清理异常行政区划 |
| POST | `/api/common/channel/parent/unusual/clear` | `commonChannel.js` | 清理异常父节点 |
| GET | `/api/common/channel/parent/list` | `commonChannel.js` | 父节点通道列表 |
| POST | `/api/common/channel/region/add` | `commonChannel.js` | 通道添加行政区划 |
| POST | `/api/common/channel/region/delete` | `commonChannel.js` | 通道删除行政区划 |
| POST | `/api/common/channel/region/device/add` | `commonChannel.js` | 按设备添加行政区划 |
| POST | `/api/common/channel/region/device/delete` | `commonChannel.js` | 按设备删除行政区划 |
| POST | `/api/common/channel/group/add` | `commonChannel.js` | 通道添加分组 |
| POST | `/api/common/channel/group/delete` | `commonChannel.js` | 通道删除分组 |
| POST | `/api/common/channel/group/device/add` | `commonChannel.js` | 按设备添加分组 |
| POST | `/api/common/channel/group/device/delete` | `commonChannel.js` | 按设备删除分组 |
| GET | `/api/common/channel/play` | `commonChannel.js` | 通道播放 |
| GET | `/api/common/channel/play/stop` | `commonChannel.js` | 停止通道播放 |
| GET | `/api/common/channel/front-end/scan/set/speed` | `commonChannel.js` | 扫描速度设置 |
| GET | `/api/common/channel/front-end/scan/set/left` | `commonChannel.js` | 扫描左边界 |
| GET | `/api/common/channel/front-end/scan/set/right` | `commonChannel.js` | 扫描右边界 |
| GET | `/api/common/channel/front-end/scan/start` | `commonChannel.js` | 扫描开始 |
| GET | `/api/common/channel/front-end/scan/stop` | `commonChannel.js` | 扫描停止 |
| GET | `/api/common/channel/front-end/preset/query` | `commonChannel.js` | 查询预置位 |
| GET | `/api/common/channel/front-end/tour/point/add` | `commonChannel.js` | 巡航点添加 |
| GET | `/api/common/channel/front-end/tour/point/delete` | `commonChannel.js` | 巡航点删除 |
| GET | `/api/common/channel/front-end/tour/speed` | `commonChannel.js` | 巡航速度 |
| GET | `/api/common/channel/front-end/tour/time` | `commonChannel.js` | 巡航时间 |
| GET | `/api/common/channel/front-end/tour/start` | `commonChannel.js` | 巡航开始 |
| GET | `/api/common/channel/front-end/tour/stop` | `commonChannel.js` | 巡航停止 |
| GET | `/api/common/channel/front-end/preset/add` | `commonChannel.js` | 预置位添加 |
| GET | `/api/common/channel/front-end/preset/call` | `commonChannel.js` | 预置位调用 |
| GET | `/api/common/channel/front-end/preset/delete` | `commonChannel.js` | 预置位删除 |
| GET | `/api/common/channel/front-end/auxiliary` | `commonChannel.js` | 辅助控制 |
| GET | `/api/common/channel/front-end/wiper` | `commonChannel.js` | 雨刷控制 |
| GET | `/api/common/channel/front-end/ptz` | `commonChannel.js` | 云台控制 |
| GET | `/api/common/channel/front-end/fi/iris` | `commonChannel.js` | 光圈控制 |
| GET | `/api/common/channel/front-end/fi/focus` | `commonChannel.js` | 聚焦控制 |
| GET | `/api/common/channel/playback/query` | `commonChannel.js` | 通道录像查询 |
| GET | `/api/common/channel/playback` | `commonChannel.js` | 通道录像回放 |
| GET | `/api/common/channel/playback/stop` | `commonChannel.js` | 停止回放 |
| GET | `/api/common/channel/playback/pause` | `commonChannel.js` | 暂停回放 |
| GET | `/api/common/channel/playback/resume` | `commonChannel.js` | 恢复回放 |
| GET | `/api/common/channel/playback/seek` | `commonChannel.js` | 拖动回放 |
| GET | `/api/common/channel/playback/speed` | `commonChannel.js` | 回放倍速 |
| GET | `/api/common/channel/map/list` | `commonChannel.js` | 地图通道列表 |
| POST | `/api/common/channel/map/save-level` | `commonChannel.js` | 保存地图层级 |
| POST | `/api/common/channel/map/reset-level` | `commonChannel.js` | 重置地图层级 |
| GET | `/api/common/channel/map/thin/clear` | `commonChannel.js` | 清除抽稀 |
| GET | `/api/common/channel/map/thin/progress` | `commonChannel.js` | 抽稀进度 |
| GET | `/api/common/channel/map/thin/save` | `commonChannel.js` | 保存抽稀 |
| POST | `/api/common/channel/map/thin/draw` | `commonChannel.js` | 执行抽稀 |
| GET | `/api/sy/camera/list/ids` | `commonChannel.js` | 测试接口 |

---

### 2.9 级联平台管理

Controller: `com.genersoft.iot.vmp.gb28181.controller.PlatformController`

| 方法 | 接口路径 | 前端 API 文件 | 说明 |
|------|---------|-------------|------|
| GET | `/api/platform/server_config` | `platform.js` | 获取国标服务配置 |
| GET | `/api/platform/query` | `platform.js` | 分页查询级联平台 |
| GET | `/api/platform/info/{id}` | `platform.js` | 查询级联平台详情 |
| POST | `/api/platform/add` | `platform.js` | 添加上级平台 |
| POST | `/api/platform/update` | `platform.js` | 更新上级平台 |
| DELETE | `/api/platform/delete` | `platform.js` | 删除上级平台 |
| GET | `/api/platform/exit/{serverGBId}` | `platform.js` | 查询平台是否存在 |
| GET | `/api/platform/channel/list` | `platform.js` | 分页查询平台通道 |
| POST | `/api/platform/channel/add` | `platform.js` | 向上级平台添加通道 |
| POST | `/api/platform/channel/device/add` | `platform.js` | 按设备添加通道 |
| POST | `/api/platform/channel/device/remove` | `platform.js` | 按设备移除通道 |
| DELETE | `/api/platform/channel/remove` | `platform.js` | 移除通道 |
| GET | `/api/platform/channel/push` | `platform.js` | 推送通道 |
| POST | `/api/platform/channel/custom/update` | `platform.js` | 更新自定义共享通道 |

---

### 2.10 行政区划

Controller: `com.genersoft.iot.vmp.gb28181.controller.RegionController`

| 方法 | 接口路径 | 前端 API 文件 | 说明 |
|------|---------|-------------|------|
| GET | `/api/region/tree/list` | `region.js` | 行政区划树 |
| DELETE | `/api/region/delete` | `region.js` | 删除行政区划 |
| GET | `/api/region/description` | `region.js` | 查询行政区划描述 |
| GET | `/api/region/addByCivilCode` | `region.js` | 通过编码添加 |
| GET | `/api/region/base/child/list` | `region.js` | 基础子节点列表 |
| POST | `/api/region/update` | `region.js` | 更新行政区划 |
| POST | `/api/region/add` | `region.js` | 添加行政区划 |
| GET | `/api/region/path` | `region.js` | 查询路径 |
| GET | `/api/region/tree/query` | `region.js` | 树查询 |

---

### 2.11 分组管理

Controller: `com.genersoft.iot.vmp.gb28181.controller.GroupController`

| 方法 | 接口路径 | 前端 API 文件 | 说明 |
|------|---------|-------------|------|
| POST | `/api/group/update` | `group.js` | 更新分组 |
| POST | `/api/group/add` | `group.js` | 添加分组 |
| GET | `/api/group/tree/list` | `group.js` | 分组树列表 |
| DELETE | `/api/group/delete` | `group.js` | 删除分组 |
| GET | `/api/group/path` | `group.js` | 查询路径 |
| GET | `/api/group/tree/query` | `group.js` | 树查询 |

---

### 2.12 报警管理

Controller: `com.genersoft.iot.vmp.vmanager.alarm.AlarmController`

| 方法 | 接口路径 | 前端 API 文件 | 说明 |
|------|---------|-------------|------|
| GET | `/api/alarm/list` | `alarm.js` | 分页查询报警列表 |
| DELETE | `/api/alarm/delete` | `alarm.js` | 删除报警 |
| DELETE | `/api/alarm/clear` | `alarm.js` | 按条件清空报警 |
| GET | `/api/alarm/snap/{id}` | - | 获取报警快照图片 |

---

### 2.13 云端录像

Controller: `com.genersoft.iot.vmp.vmanager.cloudRecord.CloudRecordController`

| 方法 | 接口路径 | 前端 API 文件 | 说明 |
|------|---------|-------------|------|
| GET | `/api/cloud/record/play/path` | `cloudRecord.js` | 获取播放路径 |
| GET | `/api/cloud/record/date/list` | `cloudRecord.js` | 查询有录像的日期 |
| GET | `/api/cloud/record/loadRecord` | `cloudRecord.js` | 加载录像文件 |
| GET | `/api/cloud/record/seek` | `cloudRecord.js` | 定位播放 |
| GET | `/api/cloud/record/speed` | `cloudRecord.js` | 设置播放速度 |
| GET | `/api/cloud/record/task/add` | `cloudRecord.js` | 添加合并任务 |
| GET | `/api/cloud/record/task/list` | `cloudRecord.js` | 查询合并任务 |
| DELETE | `/api/cloud/record/delete` | `cloudRecord.js` | 删除录像文件 |
| GET | `/api/cloud/record/list` | `cloudRecord.js` | 分页查询云端录像 |
| GET | `/api/cloud/record/collect/add` | - | 添加收藏 |
| GET | `/api/cloud/record/collect/delete` | - | 移除收藏 |
| GET | `/api/cloud/record/download/zip` | - | 下载录像压缩包 |
| GET | `/api/cloud/record/zip` | - | 下载录像压缩包（按条件） |
| GET | `/api/cloud/record/list-url` | - | 分页查询带 URL 的录像 |

---

### 2.14 录制计划

Controller: `com.genersoft.iot.vmp.vmanager.recordPlan.RecordPlanController`

| 方法 | 接口路径 | 前端 API 文件 | 说明 |
|------|---------|-------------|------|
| GET | `/api/record/plan/get` | `recordPlan.js` | 查询录制计划 |
| POST | `/api/record/plan/add` | `recordPlan.js` | 添加录制计划 |
| POST | `/api/record/plan/update` | `recordPlan.js` | 更新录制计划 |
| GET | `/api/record/plan/query` | `recordPlan.js` | 查询计划列表 |
| DELETE | `/api/record/plan/delete` | `recordPlan.js` | 删除录制计划 |
| GET | `/api/record/plan/channel/list` | `recordPlan.js` | 查询计划关联通道 |
| POST | `/api/record/plan/link` | `recordPlan.js` | 关联通道与计划 |

---

### 2.15 拉流代理

Controller: `com.genersoft.iot.vmp.streamProxy.controller.StreamProxyController`

| 方法 | 接口路径 | 前端 API 文件 | 说明 |
|------|---------|-------------|------|
| GET | `/api/proxy/list` | `streamProxy.js` | 分页查询拉流代理 |
| GET | `/api/proxy/one` | - | 查询单个代理 |
| POST | `/api/proxy/add` | `streamProxy.js` | 添加代理 |
| POST | `/api/proxy/update` | `streamProxy.js` | 更新代理 |
| GET | `/api/proxy/ffmpeg_cmd/list` | `streamProxy.js` | FFmpeg 模板列表 |
| DELETE | `/api/proxy/del` | - | 按 app/stream 移除 |
| DELETE | `/api/proxy/delete` | `streamProxy.js` | 按 ID 移除代理 |
| GET | `/api/proxy/start` | `streamProxy.js` | 开始播放代理 |
| GET | `/api/proxy/stop` | `streamProxy.js` | 停止播放代理 |

---

### 2.16 推流管理

Controller: `com.genersoft.iot.vmp.streamPush.controller.StreamPushController`

| 方法 | 接口路径 | 前端 API 文件 | 说明 |
|------|---------|-------------|------|
| GET | `/api/push/list` | `streamPush.js` | 推流列表查询 |
| POST | `/api/push/add` | `streamPush.js` | 添加推流信息 |
| POST | `/api/push/update` | `streamPush.js` | 更新推流信息 |
| POST | `/api/push/remove` | `streamPush.js` | 删除推流 |
| DELETE | `/api/push/batchRemove` | `streamPush.js` | 批量删除 |
| POST | `/api/push/save_to_gb` | `streamPush.js` | 保存到国标 |
| DELETE | `/api/push/remove_form_gb` | `streamPush.js` | 从国标移除 |
| POST | `/api/push/upload` | - | 导入 Excel 文件 |
| GET | `/api/push/start` | `streamPush.js` | 开始播放 |
| GET | `/api/push/forceClose` | - | 强制停止推流 |

---

### 2.17 服务管理

Controller: `com.genersoft.iot.vmp.vmanager.server.ServerController`

| 方法 | 接口路径 | 前端 API 文件 | 说明 |
|------|---------|-------------|------|
| GET | `/api/server/media_server/list` | `server.js` | 流媒体服务列表 |
| GET | `/api/server/media_server/online/list` | `server.js` | 在线流媒体列表 |
| GET | `/api/server/media_server/one/{id}` | `server.js` | 查询单个流媒体 |
| GET | `/api/server/media_server/check` | `server.js` | 测试流媒体服务 |
| GET | `/api/server/media_server/record/check` | `server.js` | 测试录像管理服务 |
| POST | `/api/server/media_server/save` | `server.js` | 保存流媒体服务 |
| DELETE | `/api/server/media_server/delete` | `server.js` | 移除流媒体服务 |
| GET | `/api/server/media_server/media_info` | `server.js` | 获取流信息 |
| GET | `/api/server/media_server/load` | `server.js` | 获取负载信息 |
| GET | `/api/server/system/configInfo` | `server.js` | 获取系统配置 |
| GET | `/api/server/system/info` | `server.js` | 获取系统信息 |
| GET | `/api/server/resource/info` | `server.js` | 获取资源概览 |
| GET | `/api/server/info` | `server.js` | 获取服务器信息 |
| GET | `/api/server/version` | - | 获取版本信息 |
| GET | `/api/server/config` | - | 获取配置（sip/base） |
| GET | `/api/server/map/config` | `server.js` | 地图配置 |
| GET | `/api/server/map/model-icon/list` | `server.js` | 地图模型图标 |
| GET | `/api/server/shutdown` | - | 关闭服务 |

---

### 2.18 部标设备（JT1078）

Controller: `com.genersoft.iot.vmp.jt1078.controller.JT1078Controller`

| 方法 | 接口路径 | 前端 API 文件 | 说明 |
|------|---------|-------------|------|
| GET | `/api/jt1078/terminal/list` | `jtDevice.js` | 终端列表 |
| GET | `/api/jt1078/terminal/query` | `jtDevice.js` | 查询终端 |
| POST | `/api/jt1078/terminal/update` | `jtDevice.js` | 更新终端 |
| POST | `/api/jt1078/terminal/add` | `jtDevice.js` | 添加终端 |
| DELETE | `/api/jt1078/terminal/delete` | `jtDevice.js` | 删除终端 |
| GET | `/api/jt1078/terminal/channel/list` | `jtDevice.js` | 终端通道列表 |
| POST | `/api/jt1078/terminal/channel/update` | `jtDevice.js` | 更新通道 |
| POST | `/api/jt1078/terminal/channel/add` | `jtDevice.js` | 添加通道 |
| GET | `/api/jt1078/live/start` | `jtDevice.js` | 开始点播 |
| GET | `/api/jt1078/live/stop` | `jtDevice.js` | 停止点播 |
| GET | `/api/jt1078/ptz` | `jtDevice.js` | 云台控制 |
| GET | `/api/jt1078/wiper` | `jtDevice.js` | 雨刷控制 |
| GET | `/api/jt1078/fill-light` | `jtDevice.js` | 补光灯控制 |
| GET | `/api/jt1078/record/list` | `jtDevice.js` | 录像列表 |
| GET | `/api/jt1078/playback/start` | `jtDevice.js` | 开始回放 |
| GET | `/api/jt1078/playback/downloadUrl` | `jtDevice.js` | 获取录像下载 URL |
| GET | `/api/jt1078/playback/control` | `jtDevice.js` | 回放控制 |
| GET | `/api/jt1078/playback/stop` | `jtDevice.js` | 停止回放 |
| GET | `/api/jt1078/config/get` | `jtDevice.js` | 查询配置 |
| POST | `/api/jt1078/config/set` | `jtDevice.js` | 设置配置 |
| GET | `/api/jt1078/attribute` | `jtDevice.js` | 查询属性 |
| GET | `/api/jt1078/link-detection` | `jtDevice.js` | 链路检测 |
| GET | `/api/jt1078/position-info` | `jtDevice.js` | 位置信息 |
| POST | `/api/jt1078/text-msg` | `jtDevice.js` | 发送文本消息 |
| GET | `/api/jt1078/telephone-callback` | `jtDevice.js` | 电话回拨 |
| GET | `/api/jt1078/driver-information` | `jtDevice.js` | 驾驶员信息 |
| POST | `/api/jt1078/control/factory-reset` | `jtDevice.js` | 恢复出厂设置 |
| POST | `/api/jt1078/control/reset` | `jtDevice.js` | 设备复位 |
| POST | `/api/jt1078/control/connection` | `jtDevice.js` | 断开/恢复连接 |
| GET | `/api/jt1078/control/door` | `jtDevice.js` | 车门控制 |
| GET | `/api/jt1078/media/attribute` | `jtDevice.js` | 媒体属性 |
| POST | `/api/jt1078/media/list` | `jtDevice.js` | 媒体数据列表 |
| POST | `/api/jt1078/set-phone-book` | `jtDevice.js` | 设置电话本 |
| POST | `/api/jt1078/shooting` | `jtDevice.js` | 拍照控制 |
| GET | `/api/jt1078/talk/start` | `jtDevice.js` | 开始对讲 |
| GET | `/api/jt1078/talk/stop` | `jtDevice.js` | 停止对讲 |

> 注：部标设备接口仅在 `jt1078.enable=true` 时生效。

---

### 2.19 日志管理

Controller: `com.genersoft.iot.vmp.vmanager.log.LogController`

| 方法 | 接口路径 | 前端 API 文件 | 说明 |
|------|---------|-------------|------|
| GET | `/api/log/list` | `log.js` | 查询日志列表 |

---

### 2.20 角色管理

Controller: `com.genersoft.iot.vmp.vmanager.user.RoleController`

| 方法 | 接口路径 | 前端 API 文件 | 说明 |
|------|---------|-------------|------|
| GET | `/api/role/all` | `role.js` | 查询所有角色 |

---

### 2.21 用户 API Key

Controller: `com.genersoft.iot.vmp.vmanager.user.UserApiKeyController`

| 方法 | 接口路径 | 前端 API 文件 | 说明 |
|------|---------|-------------|------|
| POST | `/api/userApiKey/remark` | `userApiKey.js` | 备注 API Key |
| GET | `/api/userApiKey/userApiKeys` | `userApiKey.js` | 分页查询 API Key |
| POST | `/api/userApiKey/enable` | `userApiKey.js` | 启用 API Key |
| POST | `/api/userApiKey/disable` | `userApiKey.js` | 禁用 API Key |
| POST | `/api/userApiKey/reset` | `userApiKey.js` | 重置 API Key |
| DELETE | `/api/userApiKey/delete` | `userApiKey.js` | 删除 API Key |
| POST | `/api/userApiKey/add` | `userApiKey.js` | 添加 API Key |

---

## 3. WebSocket 接口

配置类: `com.genersoft.iot.vmp.conf.websocket.WebSocketConfig`

| 接口路径 | 说明 | 前端引用 |
|---------|------|---------|
| `ws://<host>:<port>/channel/log` | 系统日志实时推送通道 | 前端日志页面（如存在） |

> 说明：该 WebSocket 端点仅用于服务端向客户端推送日志，不接受客户端发送消息（超过 1 byte 会被断开）。

---

## 4. SIP 协议说明

### 4.1 SIP 接口概述

SIP（Session Initiation Protocol）是后端内部与 GB28181 国标设备进行**信令通信**的协议，**不直接暴露给 Web 前端**。Web 前端通过上述 HTTP API 间接触发 SIP 操作。

### 4.2 SIP 配置参数

配置来源: `application-base.yml` / `application-docker.yml`

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `sip.ip` | `127.0.0.1` | SIP 监听 IP |
| `sip.port` | `8116` | SIP 监听端口（UDP/TCP） |
| `sip.domain` | `3402000000` | SIP 域 |
| `sip.id` | `34020000002000000001` | SIP ID |
| `sip.password` | - | SIP 密码 |
| `sip.alarm` | `true` | 是否启用报警 |

### 4.3 SIP 端口暴露

在 Docker 部署模式下：
- UDP: `${SIP_Port:-8116}`
- TCP: `${SIP_Port:-8116}`

### 4.4 前端与 SIP 的间接交互方式

Web 前端不直接发送 SIP 消息，而是通过 HTTP API 触发后端 SIP 命令，例如：

| 前端操作 | HTTP API | 后端 SIP 行为 |
|---------|----------|--------------|
| 点击播放 | `GET /api/play/start/{deviceId}/{channelId}` | 后端发送 `INVITE` 建立媒体会话 |
| 云台控制 | `GET /api/front-end/ptz/{deviceId}/{channelId}` | 后端发送 `MESSAGE`（PTZ 控制指令） |
| 录像查询 | `GET /api/gb_record/query/{deviceId}/{channelId}` | 后端发送 `MESSAGE`（RecordInfo 查询） |
| 语音广播 | `GET /api/play/broadcast/{deviceId}/{channelId}` | 后端发送 `INVITE`（语音广播） |
| 设备同步 | `GET /api/device/query/devices/{deviceId}/sync` | 后端发送 `MESSAGE`（Catalog 查询） |

---

## 5. 认证方式

### 5.1 JWT Token

- 登录接口 (`/api/user/login`) 成功后返回 `access-token`
- 后续请求需在 Header 中携带: `access-token: <token>`
- 或在请求参数中携带: `?access-token=<token>`

### 5.2 免认证接口

配置来源: `application-base.yml`

```yaml
user-settings:
  interface-authentication: false
  interface-authentication-excludes:
    - /api/**
```

默认配置下 `/api/**` 接口免认证，生产环境建议开启认证。

---

## 附录：前端 API 文件索引

| 前端文件 | 对应模块 | 后端 Controller |
|---------|---------|----------------|
| `web/src/api/user.js` | 用户管理 | `vmanager.user.UserController` |
| `web/src/api/device.js` | 国标设备 | `gb28181.controller.DeviceQuery/Control` |
| `web/src/api/play.js` | 实时点播 | `gb28181.controller.PlayController` |
| `web/src/api/playback.js` | 视频回放 | `gb28181.controller.PlaybackController` |
| `web/src/api/gbRecord.js` | 国标录像 | `gb28181.controller.GBRecordController` |
| `web/src/api/frontEnd.js` | 前端控制（按设备） | `gb28181.controller.PtzController` |
| `web/src/api/commonChannel.js` | 通用通道 | `gb28181.controller.ChannelController` |
| `web/src/api/platform.js` | 级联平台 | `gb28181.controller.PlatformController` |
| `web/src/api/region.js` | 行政区划 | `gb28181.controller.RegionController` |
| `web/src/api/group.js` | 分组管理 | `gb28181.controller.GroupController` |
| `web/src/api/alarm.js` | 报警管理 | `vmanager.alarm.AlarmController` |
| `web/src/api/cloudRecord.js` | 云端录像 | `vmanager.cloudRecord.CloudRecordController` |
| `web/src/api/recordPlan.js` | 录制计划 | `vmanager.recordPlan.RecordPlanController` |
| `web/src/api/streamProxy.js` | 拉流代理 | `streamProxy.controller.StreamProxyController` |
| `web/src/api/streamPush.js` | 推流管理 | `streamPush.controller.StreamPushController` |
| `web/src/api/server.js` | 服务管理 | `vmanager.server.ServerController` |
| `web/src/api/jtDevice.js` | 部标设备 | `jt1078.controller.JT1078Controller` |
| `web/src/api/log.js` | 日志管理 | `vmanager.log.LogController` |
| `web/src/api/role.js` | 角色管理 | `vmanager.user.RoleController` |
| `web/src/api/userApiKey.js` | API Key | `vmanager.user.UserApiKeyController` |

---

*文档结束*
