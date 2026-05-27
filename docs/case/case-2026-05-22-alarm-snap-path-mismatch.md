# 报警快照文件路径不一致导致无法查看

> 日期: 2026-05-22 | 涉及设备: 35020000201311095331 (5G摄像头) | 涉及服务: wvp, jxt-frontend

## 现象

报警管理页面中，报警记录已成功入库，回放功能正常，但点击快照图片无法显示（显示空白）。

前端请求 `GET /api/alarm/snap/{id}` 返回 204 No Content。

## 排查过程

### 1. 确认快照文件是否存在

```bash
$ docker exec polaris-wvp ls -la /opt/wvp/snap/
35020000201311095331_35020000201311005331.jpg  (48KB)
35020000201311095331_35020000201341005331.jpg  (45KB)
```

快照文件实际存在且有内容（45-48KB），说明截图抓拍成功了。

### 2. 对比数据库路径与磁盘文件

```sql
SELECT id, snap_path FROM wvp_alarm ORDER BY id DESC LIMIT 3;
```

| id | snap_path (DB 记录) | 磁盘上的实际文件 |
|---|---|---|
| 7 | `snap/alarm_35020000201311005331_1779459477736.jpg` | `35020000201311095331_35020000201311005331.jpg` |
| 6 | `snap/alarm_35020000201311005331_1779458629481.jpg` | 同上（已被覆盖） |
| 5 | `snap/alarm_35020000201341005331_1779458566001.jpg` | `35020000201311095331_35020000201341005331.jpg` |

**路径完全不一致**：DB 记录的是 `snap/alarm_<channelId>_<timestamp>.jpg`，而磁盘上的是 `<deviceId>_<channelId>.jpg`（ZLM 的流 ID 命名规则）。

### 3. 定位根因

追踪 `getSnapByAlarm` 的调用链：

```
AlarmServiceImpl.getSnapByAlarm(alarm)
  → gbChannelPlayService.getSnap(channel, callback)     // 返回 byte[] 的接口
    → PlayServiceImpl.getSnap(CommonGBChannel, ...)      // 第 1660 行
      → play(device, deviceChannel, callback)            // 发起 INVITE 点播
        → mediaServerService.getSnap(..., null, null)    // 注意：path=null, fileName=null
          → ZLM 抓帧，用流 ID 命名保存文件               // ← 磁盘上的文件来源
          → 返回 byte[]                                  // ← 但返回 null
        → callback.run(code, msg, null)                  // data=null
      → AlarmServiceImpl 回调：data==null → return       // ← 什么都没写
```

问题出在 `PlayServiceImpl.getSnap(CommonGBChannel, ErrorCallback<byte[]>)` 第 1693-1705 行的"未在播放"路径：

```java
// 调用 mediaServerService.getSnap 时 path=null, fileName=null
byte[] snapByteArray = mediaServerService.getSnap(
    data.getMediaServer(), MediaStreamUtil.RTP_APP,
    data.getStream(), 15, 1, null, null  // ← path=null, fileName=null
);
```

- `path=null, fileName=null` → ZLM 不保存到指定路径，但可能用流 ID 命名自行保存
- 返回的 `byte[]` 为 null → 回调中 `data == null` → 不写入文件

结果：ZLM 用自己的命名保存了快照，但 `AlarmServiceImpl` 没有成功将快照写入 DB 记录的路径。

### 4. 对比两个 getSnap 方法

`IPlayService` 有两个 `getSnap` 重载：

| 方法签名 | 行为 | 快照文件名 |
|---------|------|-----------|
| `getSnap(CommonGBChannel, ErrorCallback<byte[]>)` | 返回 byte[]，由调用方写入 | 调用方自己指定 |
| `getSnap(String deviceId, String channelId, String fileName, ErrorCallback)` | ZLM 直接以 fileName 保存 | 由参数指定 |

第一个方法在"未在播放"路径中传了 null 路径，导致 byte[] 为 null。第二个方法让 ZLM 直接以指定文件名保存，路径可控。

## 修复方案

### 代码修改

**文件**: `src/main/java/.../service/impl/AlarmServiceImpl.java`

改用基于文件的 `getSnap(deviceId, channelId, fileName, callback)` 方法，ZLM 直接以 DB 中记录的文件名保存：

```java
// 修改前：使用返回 byte[] 的接口，byte[] 为 null 导致文件未写入
gbChannelPlayService.getSnap(channel, (code, msg, data) -> {
    if (data == null) { return; }
    FileUtils.writeByteArrayToFile(new File(alarm.getSnapPath()), data);
});

// 修改后：使用基于文件的接口，ZLM 直接保存为指定文件名
Device device = deviceService.getDevice(channel.getDataDeviceId());
DeviceChannel deviceChannel = deviceChannelService.getOneForSourceById(channel.getGbId());
String fileName = snapPath.substring(snapPath.lastIndexOf('/') + 1);
playService.getSnap(device.getDeviceId(), deviceChannel.getDeviceId(), fileName, (code, msg, data) -> {
    if (code != 0) {
        log.warn("[报警快照] 保存失败，alarmId：{}，原因：{}", alarm.getId(), msg);
    }
});
```

新增依赖注入：`IPlayService playService`、`IDeviceService deviceService`。

### 修复后的数据流

```
1. alarm.setSnapPath("snap/alarm_35020000201311005331_1779459477736.jpg")  → 写入 DB
2. getSnapByAlarm 提取 fileName = "alarm_35020000201311005331_1779459477736.jpg"
3. playService.getSnap(deviceGbId, channelGbId, fileName, ...)
4. ZLM 点播 → 抓帧 → 保存为 "snap/alarm_35020000201311005331_1779459477736.jpg"
5. AlarmController.snap() 用 DB 中的 snapPath 找文件 → 找到 → 返回图片
```

DB 路径与磁盘文件名完全一致，快照可正常查看。

## 经验总结

1. **同一个接口的不同重载方法行为差异大** — `getSnap(CommonGBChannel, ...)` 返回 byte[]（可能为 null），`getSnap(String, String, String, ...)` 让 ZLM 直接保存文件（更可靠）
2. **验证数据流端到端一致性** — 快照保存后应确认 DB 路径与实际文件路径一致，不能只看"截图成功"的日志
3. **ZLM 的文件命名规则** — ZLM 默认用流 ID（`deviceId_channelId`）命名快照文件，如果需要自定义文件名，必须通过 API 参数指定
