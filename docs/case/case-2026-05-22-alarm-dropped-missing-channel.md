# GB28181 报警通知静默丢弃 — 设备未配置告警通道

> 日期: 2026-05-22 | 设备: 35020000201311000070 (DSJ_V8 执法记录仪) | 涉及服务: wvp

## 现象

前端报警管理页面 (`/wvp/alarm`) 查不到设备 35020000201311000070 的报警记录，但 WVP 容器日志显示已收到多次报警通知：

```
15:08:35 [AlarmServiceImpl] 收到设备报警事件，数量：1
15:08:47 [AlarmServiceImpl] 收到设备报警事件，数量：1
15:09:13 [AlarmServiceImpl] 收到设备报警事件，数量：1
15:09:25 [AlarmServiceImpl] 收到设备报警事件，数量：1
```

## 排查过程

### 1. 确认报警未入库

```sql
SELECT COUNT(*) FROM wvp_alarm;       -- 0
SELECT COUNT(*) FROM wvp_device_alarm; -- 0
```

两个报警表均为空，说明报警在内存中被丢弃，未写入数据库。

### 2. 定位丢弃点

追踪代码链路：

```
AlarmNotifyMessageHandler (收到 SIP NOTIFY)
  → publisher.deviceAlarmEventPublish()
    → AlarmServiceImpl.onApplicationEvent()
      → deviceChannelService.getOneForSource()  ← 返回 null
      → continue  ← 静默丢弃
```

关键代码 (`AlarmServiceImpl.java:86-89`)：

```java
DeviceChannel deviceChannel = channelCache.get(key,
    k -> deviceChannelService.getOneForSource(notify.getDeviceId(), notify.getChannelId()));
if (deviceChannel == null) {
    continue;  // 报警被静默丢弃
}
```

### 3. 查明根因

**报警通知内容**：

```xml
<Notify>
  <CmdType>Alarm</CmdType>
  <DeviceID>35020000201311000070</DeviceID>  <!-- channelId = 设备自身编码 -->
  <AlarmPriority>1</AlarmPriority>
  <AlarmMethod>2</AlarmMethod>
  <AlarmTime>2026-05-22T15:08:32</AlarmTime>
</Notify>
```

报警中的 `DeviceID` = `35020000201311000070`（设备自身编码）。

**数据库 wvp_device_channel 表**：

| id | device_id | data_device_id |
|---|---|---|
| 6 | uni0110011122110841240405 | 62 |

该设备只上报了 1 个子通道 `uni0110011122110841240405`，没有与 `35020000201311000070` 匹配的通道记录。

**SQL 查询** (`getOneByDeviceIdForSource`)：

```sql
WHERE data_type = 1
  AND data_device_id = 62
  AND COALESCE(gb_device_id, device_id) = '35020000201311000070'
-- 结果: 空 (只有 'uni0110011122110841240405'，不匹配)
```

**根因**：终端未配置告警通道，设备目录(Catalog)只上报了视频子通道，报警通知中的 DeviceID 无法匹配到任何通道记录，报警被静默丢弃。

### 4. 对比 GB28181 协议

根据 GB/T 28181-2016 第 9.4 节：

- **报警订阅 (SUBSCRIBE)**: 目标是**设备编码**（如类型 111）
- **布防 (SetGuard)**: 目标是**视频通道编码**（如类型 131）
- **报警通知 (NOTIFY)**: `DeviceID` 应为**产生报警的通道编码**

20 位国标编码中第 11-13 位类型编码：

| 类型编码 | 含义 |
|---------|------|
| 111 | 编码设备 (DVR/NVR) |
| 131 | 视频通道 (IPC) |
| 134 | 报警输入通道 |

本案例中 `35020000201311000070` 的类型编码为 `131`（视频通道），它既是设备编码也是通道编码（执法记录仪为单通道设备）。设备用自身编码上报报警，在协议上是合理的。

### 5. 配置告警通道后验证

在终端配置告警通道后，设备重新注册，目录查询返回 2 个通道：

```
15:28:40 [收到通道]设备: 35020000201311000070 -> 1个，1/2  ← 新增了告警通道
15:28:40 [收到通道]设备: 35020000201311000070 -> 1个，2/2
```

`wvp_device_channel` 新增记录：

| id | device_id | create_time |
|---|---|---|
| 6 | uni0110011122110841240405 | 15:07:40 |
| **7** | **35020000201311000070** | **15:28:40** |

报警成功入库：

```
15:29:10 [收到设备报警事件] 数量：1
15:29:10 [获取快照] 编号：35020000201311000070
15:29:10 [点播开始] 设备: 35020000201311000070
```

## 修复方案

### 代码修改

**文件**: `src/main/java/.../service/impl/AlarmServiceImpl.java`

当找不到匹配通道时，仍保存报警记录，不丢弃：

```java
// 修改前: 找不到通道则丢弃
if (deviceChannel == null) {
    continue;
}

// 修改后: 找不到通道仍保存，跳过快照
if (deviceChannel == null) {
    log.warn("[报警] 未找到通道 {}/{}，仍保存报警记录",
             notify.getDeviceId(), notify.getChannelId());
    alarm.setSnapPath(null);
} else {
    alarm.setChannelId(deviceChannel.getId());
    alarm.setSnapPath("snap/alarm_" + notify.getChannelId()
                       + "_" + System.currentTimeMillis() + ".jpg");
}
alarmQueue.offer(alarm);
```

### 影响评估

| 场景 | 修改前 | 修改后 |
|------|-------|-------|
| 有通道的报警 | 正常保存 | 不变 |
| 无通道的报警 | **静默丢弃** | 保存 (channelId=null, snapPath=null) |
| 前端展示 | — | 报警类型、时间、经纬度正常；通道名称为空 |

`wvp_alarm` 表的 `channel_id` 和 `snap_path` 字段均为 nullable，无需改表结构。`getSnapByAlarm()` 已有 null 检查，不会报错。

## 经验总结

1. **报警是关键业务数据，不应静默丢弃** — 即使无法关联通道，也应保存并记录告警日志
2. **单通道设备（执法记录仪、IPC）可能用设备编码作为报警 DeviceID** — 这在 GB28181 协议上是合法的，平台应兼容处理
3. **终端配置告警通道不是必须的前置条件** — 平台应做到配置也行、不配置也能收到报警
4. **排查类似问题**：先查 `wvp_alarm` 表确认是否入库 → 查 `wvp_device_channel` 确认通道是否存在 → 追踪 `AlarmServiceImpl` 的过滤逻辑
