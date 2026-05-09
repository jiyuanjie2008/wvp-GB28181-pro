# WVP-GB28181-Pro 前后端接口文档

> 本文档整理了 polaris-wvp 后端（端口 18978）与 Web 前端之间的所有接口，包括 HTTP REST API、WebSocket、SIP 信令以及 ZLMediaKit 内部回调。

---

## 目录

- [一、HTTP REST API 接口](#一http-rest-api-接口)
  - [1. 用户认证 (/auth, /api/user)](#1-用户认证-auth-apiuser)
  - [2. 角色管理 (/api/role)](#2-角色管理-apirole)
  - [3. API Key 管理 (/api/userApiKey)](#3-api-key-管理-apiuserapikey)
  - [4. 服务器管理 (/api/server)](#4-服务器管理-apiserver)
  - [5. 国标设备查询 (/api/device/query)](#5-国标设备查询-apidevicequery)
  - [6. 国标设备控制 (/api/device/control)](#6-国标设备控制-apidevicecontrol)
  - [7. 设备配置 (/api/device/config)](#7-设备配置-apideviceconfig)
  - [8. 实时视频播放 (/api/play)](#8-实时视频播放-apiplay)
  - [9. 历史回放 (/api/playback)](#9-历史回放-apiplayback)
  - [10. 国标录像 (/api/gb_record)](#10-国标录像-apigb_record)
  - [11. 前端控制/PTZ (/api/front-end)](#11-前端控制ptz-apifront-end)
  - [12. 统一通道接口 (/api/common/channel)](#12-统一通道接口-apicommonchannel)
  - [13. 统一通道前端控制 (/api/common/channel/front-end)](#13-统一通道前端控制-apicommonchannelfront-end)
  - [14. 级联平台 (/api/platform)](#14-级联平台-apiplatform)
  - [15. 行政区域 (/api/region)](#15-行政区域-apiregion)
  - [16. 业务分组 (/api/group)](#16-业务分组-apigroup)
  - [17. 移动位置 (/api/position)](#17-移动位置-apiposition)
  - [18. 流媒体信息 (/api/media)](#18-流媒体信息-apimedia)
  - [19. 拉流代理 (/api/proxy)](#19-拉流代理-apiproxy)
  - [20. 推流管理 (/api/push)](#20-推流管理-apipush)
  - [21. 云端录像 (/api/cloud/record)](#21-云端录像-apicloudrecord)
  - [22. 录像计划 (/api/record/plan)](#22-录像计划-apirecordplan)
  - [23. 报警管理 (/api/alarm)](#23-报警管理-apialarm)
  - [24. 系统日志 (/api/log)](#24-系统日志-apilog)
  - [25. RTP 收发 (/api/rtp)](#25-rtp-收发-apirtp)
  - [26. PS 流收发 (/api/ps)](#26-ps-流收发-apips)
  - [27. JT1078 终端 (/api/jt1078)](#27-jt1078-终端-apijt1078)
  - [28. 定制接口 (/api/sy)](#28-定制接口-apisy)
  - [29. 兼容接口 (/api/v1)](#29-兼容接口-apiv1)
  - [30. 测试接口 (/api/test)](#30-测试接口-apitest)
- [二、WebSocket 接口](#二websocket-接口)
- [三、SIP 信令接口](#三sip-信令接口)
  - [1. SIP 请求处理](#1-sip-请求处理)
  - [2. SIP 响应处理](#2-sip-响应处理)
  - [3. SIP MESSAGE 子消息类型](#3-sip-message-子消息类型)
  - [4. SIP 主动命令（WVP 发往设备）](#4-sip-主动命令wvp-发往设备)
- [四、ZLMediaKit 内部回调接口](#四zlmediakit-内部回调接口)
- [五、前端直接调用的非标准接口](#五前端直接调用的非标准接口)
- [六、接口汇总统计](#六接口汇总统计)

---

## 一、HTTP REST API 接口

### 1. 用户认证 (/auth, /api/user)

| 方法 | 路径 | 前端调用函数 | 说明 |
|------|------|-------------|------|
| GET | `/auth/login` | — | 简单登录校验 |
| GET | `/api/user/login` | `user.login` | 用户登录，返回 JWT token |
| GET | `/api/user/logout` | `user.logout` | 退出登录 |
| POST | `/api/user/userInfo` | `user.getUserInfo` | 获取当前登录用户信息 |
| POST | `/api/user/changePassword` | `user.changePassword` | 修改当前用户密码 |
| POST | `/api/user/changePasswordForAdmin` | `user.changePasswordForAdmin` | 管理员重置用户密码 |
| POST | `/api/user/add` | `user.add` | 添加用户 |
| DELETE | `/api/user/delete` | `user.removeById` | 删除用户 |
| GET | `/api/user/all` | — | 查询所有用户 |
| GET | `/api/user/users` | `user.queryList` | 分页查询用户 |
| ALL | `/api/user/changePushKey` | `user.changePushKey` | 更换推送密钥 |

### 2. 角色管理 (/api/role)

| 方法 | 路径 | 前端调用函数 | 说明 |
|------|------|-------------|------|
| POST | `/api/role/add` | — | 添加角色 |
| DELETE | `/api/role/delete` | — | 删除角色 |
| GET | `/api/role/all` | `role.getAll` | 查询所有角色 |

### 3. API Key 管理 (/api/userApiKey)

| 方法 | 路径 | 前端调用函数 | 说明 |
|------|------|-------------|------|
| POST | `/api/userApiKey/add` | `userApiKey.add` | 添加 API Key |
| GET | `/api/userApiKey/userApiKeys` | `userApiKey.queryList` | 分页查询 API Key |
| POST | `/api/userApiKey/enable` | `userApiKey.enable` | 启用 API Key |
| POST | `/api/userApiKey/disable` | `userApiKey.disable` | 禁用 API Key |
| POST | `/api/userApiKey/reset` | `userApiKey.reset` | 重置 API Key |
| POST | `/api/userApiKey/remark` | `userApiKey.remark` | 修改 API Key 备注 |
| DELETE | `/api/userApiKey/delete` | `userApiKey.remove` | 删除 API Key |

### 4. 服务器管理 (/api/server)

| 方法 | 路径 | 前端调用函数 | 说明 |
|------|------|-------------|------|
| GET | `/api/server/media_server/list` | `server.getMediaServerList` | 获取所有流媒体服务器 |
| GET | `/api/server/media_server/online/list` | `server.getOnlineMediaServerList` | 获取在线流媒体服务器 |
| GET | `/api/server/media_server/one/{id}` | `server.getMediaServer` | 获取指定流媒体服务器 |
| GET | `/api/server/media_server/check` | `server.checkMediaServer` | 测试流媒体服务器连通性 |
| GET | `/api/server/media_server/record/check` | `server.checkMediaServerRecord` | 测试录像服务器连通性 |
| POST | `/api/server/media_server/save` | `server.saveMediaServer` | 保存/更新流媒体服务器配置 |
| DELETE | `/api/server/media_server/delete` | `server.deleteMediaServer` | 删除流媒体服务器 |
| GET | `/api/server/media_server/media_info` | `server.getMediaInfo` | 查询流信息（按 app/stream） |
| GET | `/api/server/media_server/load` | `server.getMediaServerLoad` | 获取流媒体服务器负载 |
| GET | `/api/server/shutdown` | — | 关闭服务器 |
| GET | `/api/server/system/configInfo` | `server.getSystemConfig` | 获取系统配置（SIP、基础、端口） |
| GET | `/api/server/version` | — | 获取版本信息 |
| GET | `/api/server/config` | — | 获取配置（按 type: sip/base） |
| GET | `/api/server/system/info` | `server.getSystemInfo` | 获取系统运行信息 |
| GET | `/api/server/resource/info` | `server.getResourceInfo` | 获取资源概览（设备/通道/推流/代理数量） |
| GET | `/api/server/info` | `server.info` | 获取硬件/操作系统信息 |
| GET | `/api/server/map/config` | `server.getMapConfig` | 获取地图配置 |
| GET | `/api/server/map/model-icon/list` | `server.getModelList` | 获取地图模型图标列表 |

### 5. 国标设备查询 (/api/device/query)

| 方法 | 路径 | 前端调用函数 | 说明 |
|------|------|-------------|------|
| GET | `/api/device/query/devices` | `device.queryDevices` | 分页查询国标设备 |
| GET | `/api/device/query/devices/{deviceId}` | `device.queryDeviceOne` | 查询单个设备详情 |
| GET | `/api/device/query/devices/{deviceId}/channels` | `device.queryChannels` | 分页查询设备通道 |
| GET | `/api/device/query/streams` | `device.queryHasStreamChannels` | 查询有活跃流的通道 |
| GET | `/api/device/query/devices/{deviceId}/sync` | `device.sync` | 同步设备通道（SIP 目录查询） |
| GET | `/api/device/query/sync_status` | `device.queryDeviceSyncStatus` | 获取通道同步进度 |
| DELETE | `/api/device/query/devices/{deviceId}/delete` | `device.deleteDevice` | 删除设备 |
| GET | `/api/device/query/sub_channels/{deviceId}/{channelId}/channels` | `device.querySubChannels` | 查询子目录通道 |
| GET | `/api/device/query/channel/one` | `device.queryChannelOne` | 查询单个通道详情 |
| GET | `/api/device/query/channel/raw` | — | 获取通道原始数据（编辑用） |
| POST | `/api/device/query/channel/audio` | `device.changeChannelAudio` | 开启/关闭通道音频 |
| POST | `/api/device/query/channel/stream/identification/update/` | `device.updateChannelStreamIdentification` | 更新通道码流类型 |
| POST | `/api/device/query/transport/{deviceId}/{streamMode}` | `device.updateDeviceTransport` | 修改设备流传输模式 |
| POST | `/api/device/query/device/add` | `device.add` | 添加自定义设备 |
| POST | `/api/device/query/device/update` | `device.update` | 更新设备信息 |
| GET | `/api/device/query/devices/{deviceId}/status` | — | 查询设备状态（通过 SIP） |
| GET | `/api/device/query/alarm` | — | 查询设备报警（通过 SIP） |
| GET | `/api/device/query/info` | — | 查询设备信息（通过 SIP） |
| GET | `/api/device/query/snap/{deviceId}/{channelId}` | — | 获取设备/通道截图 |
| GET | `/api/device/query/subscribe/catalog` | `device.subscribeCatalog` | 开始/停止目录订阅 |
| GET | `/api/device/query/subscribe/mobile-position` | `device.subscribeMobilePosition` | 开始/停止位置订阅 |
| GET | `/api/device/query/subscribe/alarm` | `device.subscribeForAlarm` | 开始/停止报警订阅 |
| GET | `/api/device/query/statistics/keepalive` | `device.getKeepaliveTimeStatistics` | 获取心跳统计 |
| GET | `/api/device/query/statistics/register` | `device.getRegisterTimeStatistics` | 获取注册统计 |

### 6. 国标设备控制 (/api/device/control)

| 方法 | 路径 | 前端调用函数 | 说明 |
|------|------|-------------|------|
| GET | `/api/device/control/teleboot/{deviceId}` | — | 远程重启设备 |
| GET | `/api/device/control/record` | `device.deviceRecord` | 开始/停止设备录像 |
| GET | `/api/device/control/guard` | `device.setGuard` / `device.resetGuard` | 布防/撤防 |
| GET | `/api/device/control/reset_alarm` | — | 报警复位 |
| GET | `/api/device/control/i_frame` | — | 强制关键帧（I 帧） |
| GET | `/api/device/control/home_position` | — | 看守位控制 |
| GET | `/api/device/control/drag_zoom/zoom_in` | — | 拖拽放大 |
| GET | `/api/device/control/drag_zoom/zoom_out` | — | 拖拽缩小 |

### 7. 设备配置 (/api/device/config)

| 方法 | 路径 | 前端调用函数 | 说明 |
|------|------|-------------|------|
| GET | `/api/device/config/basicParam` | — | 设置设备基础参数 |
| GET | `/api/device/config/query` | `device.queryBasicParam` | 查询设备配置 |

### 8. 实时视频播放 (/api/play)

| 方法 | 路径 | 前端调用函数 | 说明 |
|------|------|-------------|------|
| GET | `/api/play/start/{deviceId}/{channelId}` | `play.play` | 开始实时视频 |
| GET | `/api/play/stop/{deviceId}/{channelId}` | `play.stop` | 停止实时视频 |
| POST | `/api/play/convertStop/{key}` | — | 停止转码 |
| GET/POST | `/api/play/broadcast/{deviceId}/{channelId}` | `play.broadcastStart` | 开始语音广播 |
| GET/POST | `/api/play/broadcast/stop/{deviceId}/{channelId}` | `play.broadcastStop` | 停止语音广播 |
| GET | `/api/play/ssrc` | — | 获取所有 SSRC 事务 |
| GET | `/api/play/snap` | — | 获取截图 |

### 9. 历史回放 (/api/playback)

| 方法 | 路径 | 前端调用函数 | 说明 |
|------|------|-------------|------|
| GET | `/api/playback/start/{deviceId}/{channelId}` | `playback.play` | 开始历史回放 |
| GET | `/api/playback/stop/{deviceId}/{channelId}/{stream}` | `playback.stop` | 停止历史回放 |
| GET | `/api/playback/pause/{streamId}` | `playback.pause` | 暂停回放 |
| GET | `/api/playback/resume/{streamId}` | `playback.resume` | 恢复回放 |
| GET | `/api/playback/seek/{streamId}/{seekTime}` | — | 回放拖动定位 |
| GET | `/api/playback/speed/{streamId}/{speed}` | `playback.setSpeed` | 设置回放倍速（0.25x-8x） |

### 10. 国标录像 (/api/gb_record)

| 方法 | 路径 | 前端调用函数 | 说明 |
|------|------|-------------|------|
| GET | `/api/gb_record/query/{deviceId}/{channelId}` | `gbRecord.query` | 查询设备历史录像 |
| GET | `/api/gb_record/download/start/{deviceId}/{channelId}` | `gbRecord.startDownLoad` | 开始历史录像下载 |
| GET | `/api/gb_record/download/stop/{deviceId}/{channelId}/{stream}` | `gbRecord.stopDownLoad` | 停止录像下载 |
| GET | `/api/gb_record/download/progress/{deviceId}/{channelId}/{stream}` | `gbRecord.queryDownloadProgress` | 获取下载进度 |

### 11. 前端控制/PTZ (/api/front-end)

| 方法 | 路径 | 前端调用函数 | 说明 |
|------|------|-------------|------|
| GET | `/api/front-end/common/{deviceId}/{channelId}` | — | 通用前端控制命令 |
| GET | `/api/front-end/ptz/{deviceId}/{channelId}` | `frontEnd.ptz` | PTZ 控制（云台/变倍） |
| GET | `/api/front-end/fi/iris/{deviceId}/{channelId}` | `frontEnd.iris` | 光圈控制 |
| GET | `/api/front-end/fi/focus/{deviceId}/{channelId}` | `frontEnd.focus` | 聚焦控制 |
| GET | `/api/front-end/preset/query/{deviceId}/{channelId}` | `frontEnd.queryPreset` | 查询预置位 |
| GET | `/api/front-end/preset/add/{deviceId}/{channelId}` | `frontEnd.addPreset` | 设置预置位 |
| GET | `/api/front-end/preset/call/{deviceId}/{channelId}` | `frontEnd.callPreset` | 调用预置位 |
| GET | `/api/front-end/preset/delete/{deviceId}/{channelId}` | `frontEnd.deletePreset` | 删除预置位 |
| GET | `/api/front-end/cruise/point/add/{deviceId}/{channelId}` | `frontEnd.addPointForCruise` | 添加巡航点 |
| GET | `/api/front-end/cruise/point/delete/{deviceId}/{channelId}` | `frontEnd.deletePointForCruise` | 删除巡航点 |
| GET | `/api/front-end/cruise/speed/{deviceId}/{channelId}` | `frontEnd.setCruiseSpeed` | 设置巡航速度 |
| GET | `/api/front-end/cruise/time/{deviceId}/{channelId}` | `frontEnd.setCruiseTime` | 设置巡航停留时间 |
| GET | `/api/front-end/cruise/start/{deviceId}/{channelId}` | `frontEnd.startCruise` | 开始巡航 |
| GET | `/api/front-end/cruise/stop/{deviceId}/{channelId}` | `frontEnd.stopCruise` | 停止巡航 |
| GET | `/api/front-end/scan/start/{deviceId}/{channelId}` | `frontEnd.startScan` | 开始自动扫描 |
| GET | `/api/front-end/scan/stop/{deviceId}/{channelId}` | `frontEnd.stopScan` | 停止自动扫描 |
| GET | `/api/front-end/scan/set/left/{deviceId}/{channelId}` | `frontEnd.setLeftForScan` | 设置扫描左边界 |
| GET | `/api/front-end/scan/set/right/{deviceId}/{channelId}` | `frontEnd.setRightForScan` | 设置扫描右边界 |
| GET | `/api/front-end/scan/set/speed/{deviceId}/{channelId}` | `frontEnd.setSpeedForScan` | 设置扫描速度 |
| GET | `/api/front-end/wiper/{deviceId}/{channelId}` | `frontEnd.wiper` | 雨刷控制 |
| GET | `/api/front-end/auxiliary/{deviceId}/{channelId}` | `frontEnd.auxiliary` | 辅助开关控制 |

### 12. 统一通道接口 (/api/common/channel)

| 方法 | 路径 | 前端调用函数 | 说明 |
|------|------|-------------|------|
| GET | `/api/common/channel/one` | `commonChannel.queryOne` | 按 DB ID 查询单个通道 |
| GET | `/api/common/channel/industry/list` | `commonChannel.getIndustryList` | 获取行业编码列表 |
| GET | `/api/common/channel/type/list` | `commonChannel.getTypeList` | 获取设备类型列表 |
| GET | `/api/common/channel/network/identification/list` | `commonChannel.getNetworkIdentificationList` | 获取网络标识类型 |
| POST | `/api/common/channel/update` | `commonChannel.update` | 更新通道 |
| POST | `/api/common/channel/reset` | `commonChannel.reset` | 重置通道字段 |
| POST | `/api/common/channel/add` | `commonChannel.add` | 添加通道 |
| GET | `/api/common/channel/list` | `commonChannel.getList` | 分页查询通道列表 |
| GET | `/api/common/channel/civilcode/list` | `commonChannel.getCivilCodeList` | 按行政区划查询通道 |
| GET | `/api/common/channel/civilCode/unusual/list` | `commonChannel.getUnusualCivilCodeList` | 查询孤立行政区划通道 |
| GET | `/api/common/channel/parent/unusual/list` | `commonChannel.getUnusualParentList` | 查询孤立父级通道 |
| POST | `/api/common/channel/civilCode/unusual/clear` | `commonChannel.clearUnusualCivilCodeList` | 清理孤立行政区划 |
| POST | `/api/common/channel/parent/unusual/clear` | `commonChannel.clearUnusualParentList` | 清理孤立父级 |
| GET | `/api/common/channel/parent/list` | `commonChannel.getParentList` | 按业务分组父级查询 |
| POST | `/api/common/channel/region/add` | `commonChannel.addToRegion` | 通道分配到行政区域 |
| POST | `/api/common/channel/region/delete` | `commonChannel.deleteFromRegion` | 从行政区域移除通道 |
| POST | `/api/common/channel/region/device/add` | `commonChannel.addDeviceToRegion` | 国标设备通道分配到区域 |
| POST | `/api/common/channel/region/device/delete` | `commonChannel.deleteDeviceFromRegion` | 国标设备通道从区域移除 |
| POST | `/api/common/channel/group/add` | `commonChannel.addToGroup` | 通道分配到业务分组 |
| POST | `/api/common/channel/group/delete` | `commonChannel.deleteFromGroup` | 从业务分组移除通道 |
| POST | `/api/common/channel/group/device/add` | `commonChannel.addDeviceToGroup` | 国标设备通道分配到分组 |
| POST | `/api/common/channel/group/device/delete` | `commonChannel.deleteDeviceFromGroup` | 国标设备通道从分组移除 |
| GET | `/api/common/channel/play` | `commonChannel.playChannel` | 播放通道流 |
| GET | `/api/common/channel/play/stop` | `commonChannel.stopPlayChannel` | 停止播放通道流 |
| GET | `/api/common/channel/playback/query` | `commonChannel.queryRecord` | 查询通道历史录像 |
| GET | `/api/common/channel/playback` | `commonChannel.playback` | 开始通道回放 |
| GET | `/api/common/channel/playback/stop` | `commonChannel.stopPlayback` | 停止通道回放 |
| GET | `/api/common/channel/playback/pause` | `commonChannel.pausePlayback` | 暂停通道回放 |
| GET | `/api/common/channel/playback/resume` | `commonChannel.resumePlayback` | 恢复通道回放 |
| GET | `/api/common/channel/playback/seek` | `commonChannel.seekPlayback` | 回放拖动定位 |
| GET | `/api/common/channel/playback/speed` | `commonChannel.speedPlayback` | 设置回放倍速 |
| GET | `/api/common/channel/map/list` | `commonChannel.getAllForMap` | 获取地图展示通道 |
| POST | `/api/common/channel/map/reset-level` | `commonChannel.resetLevel` | 重置地图抽稀结果 |
| POST | `/api/common/channel/map/thin/draw` | `commonChannel.drawThin` | 执行地图抽稀 |
| GET | `/api/common/channel/map/thin/clear` | `commonChannel.clearThin` | 清理未保存抽稀结果 |
| GET | `/api/common/channel/map/thin/save` | `commonChannel.saveThin` | 保存抽稀结果 |
| GET | `/api/common/channel/map/thin/progress` | `commonChannel.thinProgress` | 获取抽稀进度 |
| GET | `/api/common/channel/map/tile/{z}/{x}/{y}` | — | 获取 MVT 矢量切片 |
| GET | `/api/common/channel/map/thin/tile/{z}/{x}/{y}` | — | 获取抽稀后 MVT 矢量切片 |

### 13. 统一通道前端控制 (/api/common/channel/front-end)

| 方法 | 路径 | 前端调用函数 | 说明 |
|------|------|-------------|------|
| GET | `/api/common/channel/front-end/ptz` | `commonChannel.ptz` | PTZ 控制 |
| GET | `/api/common/channel/front-end/fi/iris` | `commonChannel.iris` | 光圈控制 |
| GET | `/api/common/channel/front-end/fi/focus` | `commonChannel.focus` | 聚焦控制 |
| GET | `/api/common/channel/front-end/preset/query` | `commonChannel.queryPreset` | 查询预置位 |
| GET | `/api/common/channel/front-end/preset/add` | `commonChannel.addPreset` | 设置预置位 |
| GET | `/api/common/channel/front-end/preset/call` | `commonChannel.callPreset` | 调用预置位 |
| GET | `/api/common/channel/front-end/preset/delete` | `commonChannel.deletePreset` | 删除预置位 |
| GET | `/api/common/channel/front-end/tour/point/add` | `commonChannel.addPointForCruise` | 添加巡航点 |
| GET | `/api/common/channel/front-end/tour/point/delete` | `commonChannel.deletePointForCruise` | 删除巡航点 |
| GET | `/api/common/channel/front-end/tour/speed` | `commonChannel.setCruiseSpeed` | 设置巡航速度 |
| GET | `/api/common/channel/front-end/tour/time` | `commonChannel.setCruiseTime` | 设置巡航停留时间 |
| GET | `/api/common/channel/front-end/tour/start` | `commonChannel.startCruise` | 开始巡航 |
| GET | `/api/common/channel/front-end/tour/stop` | `commonChannel.stopCruise` | 停止巡航 |
| GET | `/api/common/channel/front-end/scan/start` | `commonChannel.startScan` | 开始自动扫描 |
| GET | `/api/common/channel/front-end/scan/stop` | `commonChannel.stopScan` | 停止自动扫描 |
| GET | `/api/common/channel/front-end/scan/set/left` | `commonChannel.setLeftForScan` | 设置扫描左边界 |
| GET | `/api/common/channel/front-end/scan/set/right` | `commonChannel.setRightForScan` | 设置扫描右边界 |
| GET | `/api/common/channel/front-end/scan/set/speed` | `commonChannel.setSpeedForScan` | 设置扫描速度 |
| GET | `/api/common/channel/front-end/wiper` | `commonChannel.wiper` | 雨刷控制 |
| GET | `/api/common/channel/front-end/auxiliary` | `commonChannel.auxiliary` | 辅助开关控制 |

### 14. 级联平台 (/api/platform)

| 方法 | 路径 | 前端调用函数 | 说明 |
|------|------|-------------|------|
| GET | `/api/platform/server_config` | `platform.getServerConfig` | 获取本级 SIP 服务器配置 |
| GET | `/api/platform/query` | `platform.query` | 分页查询上级平台 |
| GET | `/api/platform/info/{id}` | — | 获取上级平台详情 |
| POST | `/api/platform/add` | `platform.add` | 添加上级平台 |
| POST | `/api/platform/update` | `platform.update` | 更新上级平台 |
| DELETE | `/api/platform/delete` | `platform.remove` | 删除上级平台 |
| GET | `/api/platform/exit/{serverGBId}` | `platform.exit` | 检查平台是否存在 |
| GET | `/api/platform/channel/list` | `platform.getChannelList` | 查询共享通道列表 |
| POST | `/api/platform/channel/add` | `platform.addChannel` | 共享通道到上级平台 |
| DELETE | `/api/platform/channel/remove` | `platform.removeChannel` | 移除共享通道 |
| GET | `/api/platform/channel/push` | `platform.pushChannel` | 推送通道到上级平台 |
| POST | `/api/platform/channel/device/add` | `platform.addChannelByDevice` | 按设备共享所有通道 |
| POST | `/api/platform/channel/device/remove` | `platform.removeChannelByDevice` | 按设备移除所有通道 |
| POST | `/api/platform/channel/custom/update` | `platform.updateCustomChannel` | 自定义共享通道信息 |

### 15. 行政区域 (/api/region)

| 方法 | 路径 | 前端调用函数 | 说明 |
|------|------|-------------|------|
| POST | `/api/region/add` | `region.add` | 添加行政区域 |
| GET | `/api/region/page/list` | — | 分页查询区域 |
| GET | `/api/region/tree/list` | `region.getTreeList` | 查询区域树 |
| GET | `/api/region/tree/query` | `region.queryTree` | 搜索区域 |
| POST | `/api/region/update` | `region.update` | 更新区域 |
| DELETE | `/api/region/delete` | `region.deleteRegion` | 删除区域 |
| GET | `/api/region/one` | — | 按 ID 查询区域 |
| GET | `/api/region/base/child/list` | `region.queryChildListInBase` | 获取子区域 |
| GET | `/api/region/path` | `region.queryPath` | 获取区域层级路径 |
| GET | `/api/region/sync` | — | 从通道同步区域 |
| GET | `/api/region/description` | `region.description` | 按编码获取区域描述 |
| GET | `/api/region/addByCivilCode` | `region.addByCivilCode` | 从编码文件添加区域 |

### 16. 业务分组 (/api/group)

| 方法 | 路径 | 前端调用函数 | 说明 |
|------|------|-------------|------|
| POST | `/api/group/add` | `group.add` | 添加业务分组 |
| GET | `/api/group/tree/list` | `group.getTreeList` | 查询分组树 |
| GET | `/api/group/tree/query` | `group.queryTree` | 搜索分组 |
| POST | `/api/group/update` | `group.update` | 更新分组 |
| DELETE | `/api/group/delete` | `group.deleteGroup` | 删除分组 |
| GET | `/api/group/path` | `group.getPath` | 获取分组层级路径 |

### 17. 移动位置 (/api/position)

| 方法 | 路径 | 前端调用函数 | 说明 |
|------|------|-------------|------|
| GET | `/api/position/history/{deviceId}` | 内联调用 | 查询历史 GPS 轨迹 |
| GET | `/api/position/latest/{deviceId}` | — | 查询最新位置 |
| GET | `/api/position/realtime/{deviceId}` | — | 通过 SIP 查询实时位置 |
| GET | `/api/position/subscribe/{deviceId}` | — | 订阅位置更新 |

### 18. 流媒体信息 (/api/media)

| 方法 | 路径 | 前端调用函数 | 说明 |
|------|------|-------------|------|
| GET | `/api/media/stream_info_by_app_and_stream` | — | 按 app/stream 获取流信息 |
| GET | `/api/media/getPlayUrl` | — | 按 app/stream 获取播放地址 |

### 19. 拉流代理 (/api/proxy)

| 方法 | 路径 | 前端调用函数 | 说明 |
|------|------|-------------|------|
| GET | `/api/proxy/list` | `streamProxy.queryList` | 分页查询拉流代理 |
| GET | `/api/proxy/one` | — | 按 app/stream 获取代理 |
| POST | `/api/proxy/add` | `streamProxy.add` | 添加拉流代理 |
| POST | `/api/proxy/update` | `streamProxy.update` | 更新拉流代理 |
| GET | `/api/proxy/ffmpeg_cmd/list` | `streamProxy.queryFfmpegCmdList` | 获取 FFmpeg 命令模板 |
| DELETE | `/api/proxy/del` | — | 按 app/stream 删除代理 |
| DELETE | `/api/proxy/delete` | `streamProxy.remove` | 按 ID 删除代理 |
| GET | `/api/proxy/start` | `streamProxy.play` | 开始播放代理流 |
| GET | `/api/proxy/stop` | `streamProxy.stopPlay` | 停止播放代理流 |

### 20. 推流管理 (/api/push)

| 方法 | 路径 | 前端调用函数 | 说明 |
|------|------|-------------|------|
| GET | `/api/push/list` | `streamPush.queryList` | 分页查询推流 |
| POST | `/api/push/add` | `streamPush.add` | 添加推流 |
| POST | `/api/push/update` | `streamPush.update` | 更新推流 |
| POST | `/api/push/remove` | `streamPush.remove` | 删除推流 |
| DELETE | `/api/push/batchRemove` | `streamPush.batchRemove` | 批量删除推流 |
| GET | `/api/push/start` | `streamPush.play` | 开始播放推流 |
| GET | `/api/push/forceClose` | — | 强制停止推流 |
| POST | `/api/push/upload` | — | 上传 Excel 批量导入 |

### 21. 云端录像 (/api/cloud/record)

| 方法 | 路径 | 前端调用函数 | 说明 |
|------|------|-------------|------|
| GET | `/api/cloud/record/date/list` | `cloudRecord.queryListByData` | 查询有录像的日期 |
| GET | `/api/cloud/record/list` | `cloudRecord.queryList` | 分页查询云端录像 |
| GET | `/api/cloud/record/play/path` | `cloudRecord.getPlayPath` | 获取录像回放路径 |
| GET | `/api/cloud/record/loadRecord` | `cloudRecord.loadRecord` | 加载 MP4 录像用于回放 |
| GET | `/api/cloud/record/seek` | `cloudRecord.seek` | 回放拖动定位 |
| GET | `/api/cloud/record/speed` | `cloudRecord.speed` | 设置回放倍速 |
| GET | `/api/cloud/record/task/add` | `cloudRecord.addTask` | 添加合并任务 |
| GET | `/api/cloud/record/task/list` | `cloudRecord.queryTaskList` | 查询合并任务 |
| GET | `/api/cloud/record/collect/add` | — | 添加收藏 |
| GET | `/api/cloud/record/collect/delete` | — | 删除收藏 |
| DELETE | `/api/cloud/record/delete` | `cloudRecord.deleteRecord` | 按 ID 删除录像文件 |
| GET | `/api/cloud/record/download/zip` | — | 按 ID 下载录像 ZIP |
| GET | `/api/cloud/record/zip` | — | 按查询条件下载录像 ZIP |
| GET | `/api/cloud/record/list-url` | — | 查询带下载链接的录像 |

### 22. 录像计划 (/api/record/plan)

| 方法 | 路径 | 前端调用函数 | 说明 |
|------|------|-------------|------|
| POST | `/api/record/plan/add` | `recordPlan.addPlan` | 添加录像计划 |
| POST | `/api/record/plan/link` | `recordPlan.linkPlan` | 关联通道到录像计划 |
| GET | `/api/record/plan/get` | `recordPlan.getPlan` | 获取录像计划 |
| GET | `/api/record/plan/query` | `recordPlan.queryList` | 分页查询录像计划 |
| GET | `/api/record/plan/channel/list` | `recordPlan.queryChannelList` | 查询计划关联的通道 |
| POST | `/api/record/plan/update` | `recordPlan.update` | 更新录像计划 |
| DELETE | `/api/record/plan/delete` | `recordPlan.deletePlan` | 删除录像计划 |

### 23. 报警管理 (/api/alarm)

| 方法 | 路径 | 前端调用函数 | 说明 |
|------|------|-------------|------|
| GET | `/api/alarm/list` | `alarm.getAlarmList` | 分页查询报警 |
| DELETE | `/api/alarm/delete` | `alarm.deleteAlarms` | 按 ID 列表删除报警 |
| DELETE | `/api/alarm/clear` | `alarm.clearAlarms` | 按条件清除报警 |
| GET | `/api/alarm/snap/{id}` | — | 获取报警截图 |

### 24. 系统日志 (/api/log)

| 方法 | 路径 | 前端调用函数 | 说明 |
|------|------|-------------|------|
| GET | `/api/log/list` | `log.queryList` | 查询日志文件列表 |
| GET | `/api/log/file/{fileName}` | 内联调用 (fetch) | 下载日志文件 |

### 25. RTP 收发 (/api/rtp)

| 方法 | 路径 | 前端调用函数 | 说明 |
|------|------|-------------|------|
| GET | `/api/rtp/receive/open` | — | 开启 RTP 接收 |
| GET | `/api/rtp/receive/close` | — | 关闭 RTP 接收 |
| GET | `/api/rtp/send/start` | — | 开始发送 RTP 流 |
| GET | `/api/rtp/send/stop` | — | 停止发送 RTP 流 |

### 26. PS 流收发 (/api/ps)

| 方法 | 路径 | 前端调用函数 | 说明 |
|------|------|-------------|------|
| GET | `/api/ps/receive/open` | — | 开启 PS 流接收 |
| GET | `/api/ps/receive/close` | — | 关闭 PS 流接收 |
| GET | `/api/ps/send/start` | — | 开始发送 PS 流 |
| GET | `/api/ps/send/stop` | — | 停止发送 PS 流 |
| GET | `/api/ps/getTestPort` | — | 获取可用端口（测试） |

### 27. JT1078 终端 (/api/jt1078)

条件启用：`jt1078.enable=true`

#### 终端设备管理

| 方法 | 路径 | 前端调用函数 | 说明 |
|------|------|-------------|------|
| GET | `/api/jt1078/terminal/list` | `jtDevice.queryDevices` | 分页查询 JT 终端 |
| GET | `/api/jt1078/terminal/query` | `jtDevice.queryDeviceById` | 查询单个终端 |
| POST | `/api/jt1078/terminal/add` | `jtDevice.add` | 添加终端 |
| POST | `/api/jt1078/terminal/update` | `jtDevice.update` | 更新终端 |
| DELETE | `/api/jt1078/terminal/delete` | `jtDevice.deleteDevice` | 删除终端 |
| GET | `/api/jt1078/terminal/channel/list` | `jtDevice.queryChannels` | 查询终端通道 |
| GET | `/api/jt1078/terminal/channel/one` | — | 查询单个通道 |
| POST | `/api/jt1078/terminal/channel/add` | `jtDevice.addChannel` | 添加通道 |
| POST | `/api/jt1078/terminal/channel/update` | `jtDevice.updateChannel` | 更新通道 |
| DELETE | `/api/jt1078/terminal/channel/delete` | — | 删除通道 |

#### 实时视频/语音

| 方法 | 路径 | 前端调用函数 | 说明 |
|------|------|-------------|------|
| GET | `/api/jt1078/live/start` | `jtDevice.play` | 开始 JT 实时视频 |
| GET | `/api/jt1078/live/stop` | `jtDevice.stopPlay` | 停止 JT 实时视频 |
| GET | `/api/jt1078/live/pause` | — | 暂停实时视频 |
| GET | `/api/jt1078/live/continue` | — | 恢复实时视频 |
| GET | `/api/jt1078/live/switch` | — | 切换码流（主/子） |
| GET | `/api/jt1078/talk/start` | `jtDevice.startTalk` | 开始语音对讲 |
| GET | `/api/jt1078/talk/stop` | `jtDevice.stopTalk` | 停止语音对讲 |

#### 录像回放/下载

| 方法 | 路径 | 前端调用函数 | 说明 |
|------|------|-------------|------|
| GET | `/api/jt1078/record/list` | `jtDevice.queryRecordList` | 查询录像资源列表 |
| GET | `/api/jt1078/playback/start` | `jtDevice.startPlayback` | 开始录像回放 |
| GET | `/api/jt1078/playback/control` | `jtDevice.controlPlayback` | 回放控制（暂停/倍速/定位） |
| GET | `/api/jt1078/playback/stop` | `jtDevice.stopPlayback` | 停止录像回放 |
| GET | `/api/jt1078/playback/downloadUrl` | `jtDevice.getRecordTempUrl` | 获取录像下载临时链接 |
| GET | `/api/jt1078/playback/download` | — | 下载录像文件 |

#### 终端控制

| 方法 | 路径 | 前端调用函数 | 说明 |
|------|------|-------------|------|
| GET | `/api/jt1078/ptz` | `jtDevice.ptz` | PTZ 控制 |
| GET | `/api/jt1078/wiper` | `jtDevice.wiper` | 雨刷控制 |
| GET | `/api/jt1078/fill-light` | `jtDevice.fillLight` | 补光灯控制 |
| GET | `/api/jt1078/config/get` | `jtDevice.queryConfig` | 查询终端参数 |
| POST | `/api/jt1078/config/set` | `jtDevice.setConfig` | 设置终端参数 |
| GET | `/api/jt1078/attribute` | `jtDevice.queryAttribute` | 查询终端属性 |
| GET | `/api/jt1078/position-info` | `jtDevice.queryPosition` | 查询位置信息 |
| GET | `/api/jt1078/link-detection` | `jtDevice.linkDetection` | 链路检测 |
| POST | `/api/jt1078/text-msg` | `jtDevice.sendTextMessage` | 发送文本消息 |
| GET | `/api/jt1078/telephone-callback` | `jtDevice.telephoneCallback` | 电话回拨 |
| POST | `/api/jt1078/set-phone-book` | `jtDevice.setPhoneBook` | 设置电话簿 |
| GET | `/api/jt1078/driver-information` | `jtDevice.queryDriverInfo` | 查询驾驶员信息 |
| GET | `/api/jt1078/control/door` | `jtDevice.controlDoor` | 车门锁控制 |
| POST | `/api/jt1078/control/connection` | `jtDevice.connection` | 终端连接控制 |
| POST | `/api/jt1078/control/reset` | `jtDevice.reset` | 终端复位 |
| POST | `/api/jt1078/control/factory-reset` | `jtDevice.factoryReset` | 恢复出厂设置 |
| POST | `/api/jt1078/confirmation-alarm-message` | — | 手动确认报警 |
| GET | `/api/jt1078/control/temp-position-tracking` | — | 临时位置跟踪 |
| POST | `/api/jt1078/shooting` | `jtDevice.shooting` | 立即拍照 |
| GET | `/api/jt1078/snap` | — | 抓拍图片 |
| GET | `/api/jt1078/media/attribute` | `jtDevice.queryMediaAttribute` | 查询终端音视频属性 |
| POST | `/api/jt1078/media/list` | `jtDevice.queryMediaData` | 查询存储多媒体数据 |
| GET | `/api/jt1078/media/upload/one/upload` | 内联调用 (fetch) | 上传/下载单个多媒体数据 |
| GET | `/api/jt1078/media/upload/one/delete` | — | 删除单个多媒体数据 |
| GET | `/api/jt1078/record/start` | — | 开始录音 |
| GET | `/api/jt1078/record/stop` | — | 停止录音 |

#### JT1078 区域/路线管理

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/jt1078/area/circle/add` | 追加圆形区域 |
| POST | `/api/jt1078/area/circle/edit` | 修改圆形区域 |
| POST | `/api/jt1078/area/circle/update` | 更新圆形区域 |
| GET | `/api/jt1078/area/circle/delete` | 删除圆形区域 |
| GET | `/api/jt1078/area/circle/query` | 查询圆形区域 |
| POST | `/api/jt1078/area/rectangle/add` | 追加矩形区域 |
| POST | `/api/jt1078/area/rectangle/edit` | 修改矩形区域 |
| POST | `/api/jt1078/area/rectangle/update` | 更新矩形区域 |
| GET | `/api/jt1078/area/rectangle/delete` | 删除矩形区域 |
| GET | `/api/jt1078/area/rectangle/query` | 查询矩形区域 |
| POST | `/api/jt1078/area/polygon/set` | 设置多边形区域 |
| GET | `/api/jt1078/area/polygon/delete` | 删除多边形区域 |
| GET | `/api/jt1078/area/polygon/query` | 查询多边形区域 |
| POST | `/api/jt1078/route/set` | 设置路线 |
| GET | `/api/jt1078/route/delete` | 删除路线 |
| GET | `/api/jt1078/route/query` | 查询路线 |

### 28. 定制接口 (/api/sy)

条件启用：`sy.enable=true`

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/sy/camera/list` | 查询当前分组摄像头列表 |
| GET | `/api/sy/camera/list-with-child` | 查询含子分组摄像头 |
| GET | `/api/sy/camera/cont-with-child` | 摄像头数量与在线数 |
| GET | `/api/sy/camera/one` | 查询单个摄像头 |
| GET | `/api/sy/camera/update` | 更新摄像头 |
| POST | `/api/sy/camera/list/ids` | 按 ID 列表查询摄像头 |
| GET | `/api/sy/camera/list/box` | 按矩形范围查询摄像头 |
| POST | `/api/sy/camera/list/polygon` | 按多边形范围查询摄像头 |
| GET | `/api/sy/camera/list/circle` | 按圆形范围查询摄像头 |
| GET | `/api/sy/camera/list/address` | 按地址查询摄像头 |
| GET | `/api/sy/camera/control/play` | 播放摄像头流 |
| GET | `/api/sy/camera/control/stop` | 停止播放 |
| GET | `/api/sy/camera/control/ptz` | 摄像头 PTZ 控制 |
| GET | `/api/sy/camera/list-for-mobile` | 移动端摄像头列表 |
| GET | `/api/sy/push/play` | 推流播放地址 |
| GET | `/api/sy/push/play-without-check` | 推流播放地址（无鉴权） |
| GET | `/api/sy/record/collect/add` | 添加云端录像收藏 |
| GET | `/api/sy/record/collect/delete` | 删除云端录像收藏 |
| GET | `/api/sy/record/zip` | 下载录像 ZIP |
| GET | `/api/sy/record/list-url` | 查询带链接的录像 |
| GET | `/api/sy/forceClose` | 强制停止推流 |
| GET | `/api/sy/camera/meeting/list` | 查询会议设备摄像头 |

### 29. 兼容接口 (/api/v1)

旧版 API 兼容层。

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/getserverinfo` | 获取服务器信息 |
| GET | `/api/v1/userinfo` | 获取用户信息 |
| GET | `/api/v1/login` | 登录 |
| GET | `/api/v1/device/list` | 设备列表（旧格式） |
| GET | `/api/v1/device/channellist` | 通道列表（旧格式） |
| GET | `/api/v1/device/fetchpreset` | 获取预置位 |
| GET | `/api/v1/stream/start` | 开始实时流 |
| GET | `/api/v1/stream/stop` | 停止实时流 |
| GET | `/api/v1/stream/touch` | 保活实时流 |
| GET | `/api/v1/control/ptz` | PTZ 控制（旧） |
| GET | `/api/v1/control/preset` | 预置位控制（旧） |

### 30. 测试接口 (/api/test)

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/test/hook/list` | 列出所有 hook 订阅 |
| GET | `/api/test/redis` | 测试 Redis invite 查询 |

---

## 二、WebSocket 接口

| WebSocket URI | 前端调用位置 | 说明 |
|------|-------------|------|
| `ws://{host}/channel/log` | `views/operations/realLog.vue` | 实时日志推送。前端建立 WebSocket 连接后，服务端持续推送日志消息。生产环境 HTTPS 使用 `wss://`，开发模式通过 `VUE_APP_BASE_API` 代理。认证 token 通过 WebSocket 子协议参数传递。 |

---

## 三、SIP 信令接口

SIP 信令不直接与前端交互，而是 WVP 后端与国标设备/上级平台之间的通信协议。前端通过 HTTP API 间接触发 SIP 命令。

### 1. SIP 请求处理

WVP 监听端口（默认 8116，UDP/TCP），处理以下 SIP 方法：

| SIP 方法 | 处理器 | 说明 |
|---------|--------|------|
| **REGISTER** | `RegisterRequestProcessor` | 设备注册/注销。支持摘要认证（401 挑战）、密码校验、设备上下线管理 |
| **INVITE** | `InviteRequestProcessor` | 媒体会话建立。处理来自上级平台（级联）和设备（语音广播）的 INVITE。解析 SDP 获取媒体参数 |
| **BYE** | `ByeRequestProcessor` | 会话终止。停止媒体流、释放 SSRC 资源、通知上级平台 |
| **MESSAGE** | `MessageRequestProcessor` | GB28181 XML 消息处理中枢。按 XML 根元素分发到子处理器（Control/Notify/Query/Response） |
| **NOTIFY** | `NotifyRequestProcessor` | 订阅通知。处理目录变更、报警、移动位置通知 |
| **SUBSCRIBE** | `SubscribeRequestProcessor` | 订阅请求。支持移动位置订阅（GPS 间隔）、目录订阅、通用订阅 |
| **ACK** | `AckRequestProcessor` | INVITE 完成确认。触发流媒体服务器开始发送 RTP（支持 TCP 主动/被动模式） |
| **CANCEL** | `CancelRequestProcessor` | 取消请求（级联场景，待完善） |
| **INFO** | `InfoRequestProcessor` | 回放控制（级联场景）。转发 MANSRTSP 回放控制命令（播放/暂停/定位/倍速） |

### 2. SIP 响应处理

| SIP 方法 | 处理器 | 说明 |
|---------|--------|------|
| **REGISTER** | `RegisterResponseProcessor` | 处理向外注册的响应（级联到上级平台）。401 则带摘要认证重新注册，200 OK 标记平台在线 |
| **INVITE** | `InviteResponseProcessor` | 处理 INVITE 响应。200 OK 时解析 SDP 并发送 ACK 完成媒体会话 |
| **BYE** | `ByeResponseProcessor` | BYE 响应处理 |
| **CANCEL** | `CancelResponseProcessor` | CANCEL 响应处理 |

### 3. SIP MESSAGE 子消息类型

#### Response（设备响应）

| 消息类型 | 处理器 | 说明 |
|---------|--------|------|
| Catalog | `CatalogResponseMessageHandler` | 设备目录查询响应 |
| DeviceInfo | `DeviceInfoResponseMessageHandler` | 设备信息查询响应 |
| DeviceStatus | `DeviceStatusResponseMessageHandler` | 设备状态查询响应 |
| RecordInfo | `RecordInfoResponseMessageHandler` | 录像信息查询响应 |
| MobilePosition | `MobilePositionResponseMessageHandler` | 移动位置查询响应 |
| DeviceControl | `DeviceControlResponseMessageHandler` | 设备控制响应 |
| ConfigDownload | `ConfigDownloadResponseMessageHandler` | 配置下载响应 |
| PresetQuery | `PresetQueryResponseMessageHandler` | 预置位查询响应 |
| Broadcast | `BroadcastResponseMessageHandler` | 语音广播响应 |
| Alarm | `AlarmResponseMessageHandler` | 报警查询响应 |

#### Notify（设备通知）

| 消息类型 | 处理器 | 说明 |
|---------|--------|------|
| Keepalive | `KeepaliveNotifyMessageHandler` | 设备心跳 |
| Catalog | `CatalogNotifyMessageHandler` | 目录变更通知 |
| MobilePosition | `MobilePositionNotifyMessageHandler` | 移动位置通知 |
| MediaStatus | `MediaStatusNotifyMessageHandler` | 媒体状态通知 |
| Broadcast | `BroadcastNotifyMessageHandler` | 广播通知 |
| Alarm | `AlarmNotifyMessageHandler` | 报警通知 |

#### Query（上级平台查询）

| 消息类型 | 处理器 | 说明 |
|---------|--------|------|
| Catalog | `CatalogQueryMessageHandler` | 上级目录查询 |
| DeviceInfo | `DeviceInfoQueryMessageHandler` | 上级设备信息查询 |
| DeviceStatus | `DeviceStatusQueryMessageHandler` | 上级设备状态查询 |
| RecordInfo | `RecordInfoQueryMessageHandler` | 上级录像查询 |
| Alarm | `AlarmQueryMessageHandler` | 上级报警查询 |

#### Control（上级平台控制）

| 消息类型 | 处理器 | 说明 |
|---------|--------|------|
| DeviceControl | `DeviceControlQueryMessageHandler` | 上级设备控制命令 |

### 4. SIP 主动命令（WVP 发往设备）

以下命令由前端 HTTP API 触发，WVP 通过 SIP MESSAGE/INVITE 发送到设备：

| 命令 | 触发 API | 说明 |
|------|---------|------|
| PTZ 控制 | `/api/front-end/ptz` | 云台方向/变倍/速度 |
| 前端控制 | `/api/front-end/common` | PTZ/聚焦/预置位/巡航/扫描/辅助 |
| 实时视频 | `/api/play/start` | INVITE 发起实时视频请求 |
| 历史回放 | `/api/playback/start` | INVITE 发起回放请求（含时间范围） |
| 录像下载 | `/api/gb_record/download/start` | INVITE 发起下载请求（含倍速） |
| 停止流 | `/api/play/stop`, `/api/playback/stop` | BYE 停止媒体会话 |
| 语音对讲 | `/api/play/broadcast` | INVITE 发起双向音频 |
| 回放控制 | `/api/playback/pause/resume/seek/speed` | 发送回放控制命令 |
| 语音广播 | `/api/play/broadcast` | INVITE 发起广播 |
| 录像控制 | `/api/device/control/record` | 开始/停止设备录像 |
| 远程重启 | `/api/device/control/teleboot` | 远程重启设备 |
| 布防/撤防 | `/api/device/control/guard` | 报警布防/撤防 |
| 报警复位 | `/api/device/control/reset_alarm` | 清除报警 |
| 强制 I 帧 | `/api/device/control/i_frame` | 强制关键帧 |
| 看守位 | `/api/device/control/home_position` | 看守位控制 |
| 设备配置 | `/api/device/config/basicParam` | 配置命令 |
| 设备状态查询 | `/api/device/query/.../status` | 查询设备状态 |
| 设备信息查询 | `/api/device/query/info` | 查询设备信息 |
| 目录查询 | `/api/device/query/.../sync` | 同步设备通道目录 |
| 录像查询 | `/api/gb_record/query` | 查询设备录像列表 |
| 报警查询 | `/api/device/query/alarm` | 查询报警历史 |
| 配置查询 | `/api/device/config/query` | 查询设备配置 |
| 预置位查询 | `/api/front-end/preset/query` | 查询预置位 |
| 位置查询 | `/api/position/realtime` | 查询 GPS 位置 |
| 位置订阅 | `/api/position/subscribe` | 订阅/取消位置更新 |
| 报警订阅 | `/api/device/query/subscribe/alarm` | 订阅/取消报警通知 |
| 目录订阅 | `/api/device/query/subscribe/catalog` | 订阅/取消目录变更 |
| 拖拽缩放 | `/api/device/control/drag_zoom/*` | 拖拽放大/缩小 |

---

## 四、ZLMediaKit 内部回调接口

这些接口由 ZLMediaKit 流媒体服务器回调，不对外暴露。

### ZLM 回调 (`/index/hook`)

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/index/hook/on_server_keepalive` | ZLM 服务器心跳（默认 10s），更新服务器状态 |
| POST | `/index/hook/on_play` | 播放鉴权，校验 RTSP/RTMP/HTTP-FLV/WS-FLV/HLS 播放请求 |
| POST | `/index/hook/on_publish` | 推流鉴权，校验推流请求并返回流参数 |
| POST | `/index/hook/on_stream_changed` | 流注册/注销事件，触发 MediaArrivalEvent 或 MediaDepartureEvent |
| POST | `/index/hook/on_stream_none_reader` | 无人观看事件，决定是否关闭流 |
| POST | `/index/hook/on_stream_not_found` | 流未找到事件，触发按需拉流 |
| POST | `/index/hook/on_server_started` | ZLM 服务器启动通知，处理崩溃重启 |
| POST | `/index/hook/on_send_rtp_stopped` | RTP 发送停止事件 |
| POST | `/index/hook/on_rtp_server_timeout` | RTP 接收超时事件 |
| POST | `/index/hook/on_record_mp4` | MP4 录制完成事件 |

### ABL 流媒体回调 (`/index/hook/abl`)

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/index/hook/abl/on_server_keepalive` | ABL 服务器心跳 |
| POST | `/index/hook/abl/on_play` | ABL 播放回调 |
| POST | `/index/hook/abl/on_publish` | ABL 推流回调 |
| POST | `/index/hook/abl/on_record_progress` | 录制进度回调 |
| POST | `/index/hook/abl/on_stream_not_arrive` | 流未到达回调 |
| POST | `/index/hook/abl/on_delete_record_mp4` | MP4 删除回调 |
| POST | `/index/hook/abl/on_stream_arrive` | 流到达回调 |
| POST | `/index/hook/abl/on_stream_none_reader` | 无人观看回调 |
| POST | `/index/hook/abl/on_stream_not_found` | 流未找到回调 |
| POST | `/index/hook/abl/on_server_started` | ABL 服务器启动回调 |
| POST | `/index/hook/abl/on_record_mp4` | MP4 录制完成回调 |
| POST | `/index/hook/abl/on_stream_disconnect` | 流断开回调 |

---

## 五、前端直接调用的非标准接口

以下接口在 Vue 组件中直接通过 `$axios` 或 `fetch` 调用，未经过 `src/api/` 模块层封装：

| URL 路径 | 方法 | 调用位置 | 说明 |
|---------|------|---------|------|
| `/api/ptz/front_end_command/{deviceId}/{channelId}` | POST | `jtDevicePlayer.vue` | 原始 PTZ 前端控制命令（预置位/巡航/扫描） |
| `/api/platform/catalog/add` | POST | `catalogEdit.vue` | 平台目录添加 |
| `/api/platform/catalog/edit` | POST | `catalogEdit.vue` | 平台目录编辑 |
| `/api/position/history/{deviceId}` | GET | `queryTrace.vue` | 位置轨迹历史查询 |
| `/api/log/file/{fileName}` | GET (fetch) | `historyLog.vue` | 下载日志文件 |
| `/record_proxy/api/record/delete` | DELETE | `historyLog.vue` | 删除录像（通过 nginx 代理） |
| `/api/jt1078/media/upload/one/upload` | GET (fetch) | `jtDevice/channel/index.vue` | 下载多媒体文件 |
| `/zlm/{mediaServerId}/index/api/getMediaInfo` | GET | `jtDevicePlayer.vue` | ZLMediaKit 直接查询媒体编码信息 |

---

## 六、接口汇总统计

### 按接口类别统计

| 类别 | 数量 |
|------|------|
| HTTP REST API（前端调用） | ~290 |
| WebSocket 接口 | 1 |
| SIP 请求处理器 | 9 |
| SIP 响应处理器 | 4 |
| SIP MESSAGE 子类型 | 21 |
| SIP 主动命令（WVP→设备） | 30+ |
| ZLM Hook 回调 | 10 |
| ABL Hook 回调 | 12 |
| 前端非标准内联调用 | 8 |

### 按功能模块统计（前端 API 层）

| 模块 | 前端文件 | 接口数 |
|------|---------|--------|
| 通道管理 | `commonChannel.js` | 59 |
| JT1078 终端 | `jtDevice.js` | 36 |
| 国标设备 | `device.js` | 25 |
| 前端控制/PTZ | `frontEnd.js` | 20 |
| 服务器管理 | `server.js` | 15 |
| 级联平台 | `platform.js` | 13 |
| 用户管理 | `user.js` | 9 |
| 云端录像 | `cloudRecord.js` | 9 |
| 行政区域 | `region.js` | 9 |
| 拉流代理 | `streamProxy.js` | 8 |
| 推流管理 | `streamPush.js` | 8 |
| 录像计划 | `recordPlan.js` | 7 |
| API Key 管理 | `userApiKey.js` | 7 |
| 业务分组 | `group.js` | 6 |
| 国标录像 | `gbRecord.js` | 4 |
| 实时播放 | `play.js` | 4 |
| 历史回放 | `playback.js` | 5 |
| 报警管理 | `alarm.js` | 3 |
| 系统日志 | `log.js` | 1 |
| 角色管理 | `role.js` | 1 |

### 通信协议一览

| 协议 | 端口 | 用途 |
|------|------|------|
| HTTP | 18978 (WVP) / 8080 (Nginx) | REST API、前端页面 |
| WebSocket | `/channel/log` | 实时日志推送 |
| SIP (UDP/TCP) | 8116 | GB28181 设备信令 |
| RTMP | 10001 | 收流 |
| RTSP | 10002 | 收流 |
| RTP | 10003 | 收流 |
| HTTP (ZLM 内部) | 80 (容器内) | ZLM Hook 回调、流媒体 API |
