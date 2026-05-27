# 截图临时流泄漏（F11）

> 日期: 2026-05-24 | 涉及服务: wvp, ZLMediaKit | 严重级别: 中高 | 修复状态: 已上线

## TL;DR

`PlayServiceImpl.getSnap` 在通道无活跃流时会通过 `play()` 临时拉起一路 RTP 流来截图，截图完成后**未调用 `stop()`**，依赖 ZLM `on_stream_none_reader`（默认 ~20 秒）兜底关流。在报警密集场景下会快速耗尽 ZLM 端口/SSRC 资源；当 `stream-on-demand=false` 时演变为**永久泄漏**。修复后所有 5 次截图风暴测试 ZLM 资源 0 累积。

## 一、问题位置

`@/d:/JXT/jxt-evidence-system/wvp-GB28181-pro/src/main/java/com/genersoft/iot/vmp/gb28181/service/impl/PlayServiceImpl.java`

修复前（节选 1644-1656）：

```java
MediaServer newMediaServerItem = getNewMediaServerItem(device);
play(newMediaServerItem, deviceId, channelId, null, (code, msg, data) -> {
    if (code == InviteErrorCode.SUCCESS.getCode()) {
        InviteInfo inviteInfoForPlay = inviteStreamService.getInviteInfoByDeviceAndChannel(InviteSessionType.PLAY, channel.getId());
        if (inviteInfoForPlay != null && inviteInfoForPlay.getStreamInfo() != null) {
            getSnap(deviceId, channelId, fileName, errorCallback);   // 递归回到「已存在流」分支
        } else {
            errorCallback.run(InviteErrorCode.FAIL.getCode(), InviteErrorCode.FAIL.getMsg(), null);
        }
    } else {
        errorCallback.run(InviteErrorCode.FAIL.getCode(), InviteErrorCode.FAIL.getMsg(), null);
    }
});
// ← 方法结束，没有 stop()
```

`getSnap(CommonGBChannel,...)`（用于国标级联）有完全相同的泄漏模式。

## 二、危害分析

| 维度 | 影响 |
|------|------|
| 泄漏窗口 | ZLM `streamNoneReaderDelayMS` 默认 ~20 秒。20 秒内每个泄漏流持续占用 1 个 ZLM 端口 + 1 个 SSRC + 摄像头上行带宽 |
| 配置陷阱 | `user-settings.stream-on-demand=false` 时（合法配置），所有截图永久泄漏直至设备掉线或人工干预 |
| 报警风暴放大 | `AlarmServiceImpl.handlerCatchDataList.forEach(this::getSnapByAlarm)` 是 `@Async`，多通道并发触发时每路单独泄漏 |
| 资源上限 | ZLM SSRC 池有限（GB28181 通常 1000~9999），WVP RTP 端口段也有限。密集报警 2-3 分钟内能打满 |
| 次生影响 | 摄像头并发输出有限（部分国标设备最大 4 路），泄漏可能阻塞真实用户点播 |

## 三、根因还原

事件链（修复前）：

1. `AlarmServiceImpl.getSnapByAlarm` / `PlayController.getSnap` 调用 `playService.getSnap(...)`
2. 通道无活跃流，进入 1644-1656 分支：`play()` 发 SIP INVITE，摄像头开始推 RTP 到 ZLM
3. ZLM `on_publish` hook 触发 `play()` 回调，wvp 递归调用 `getSnap()` 走 1626-1641 分支截图
4. 截图返回，方法结束，**ZLM 流仍在运行**
5. 等待 ZLM `on_stream_none_reader` 检测「无观看者」，~20 秒后才回调 wvp 关流

## 四、修复方案

采用「截图完成立即 stop」方案，复用现成的 `stop(InviteSessionType.PLAY, device, channel, stream)`（@`/d:/JXT/jxt-evidence-system/wvp-GB28181-pro/src/main/java/com/genersoft/iot/vmp/gb28181/service/impl/PlayServiceImpl.java`:1742）。

### 修复点 1：`getSnap(String, String, String, ErrorCallback)` line 1644-1675

把原递归改为「就地截图 + try/finally stop」：

```java
play(newMediaServerItem, deviceId, channelId, null, (code, msg, data) -> {
    StreamInfo streamInfo = (data instanceof StreamInfo) ? (StreamInfo) data : null;
    try {
        if (code == InviteErrorCode.SUCCESS.getCode() && streamInfo != null && streamInfo.getMediaServer() != null) {
            String path = "snap";
            log.info("[请求截图-临时流]: {}", fileName);
            mediaServerService.getSnap(streamInfo.getMediaServer(), MediaStreamUtil.RTP_APP,
                    streamInfo.getStream(), 15, 1, path, fileName);
            File snapFile = new File(path + File.separator + fileName);
            if (snapFile.exists()) {
                errorCallback.run(InviteErrorCode.SUCCESS.getCode(), InviteErrorCode.SUCCESS.getMsg(), snapFile.getAbsoluteFile());
            } else {
                errorCallback.run(InviteErrorCode.FAIL.getCode(), InviteErrorCode.FAIL.getMsg(), null);
            }
        } else {
            errorCallback.run(InviteErrorCode.FAIL.getCode(),
                    msg != null ? msg : InviteErrorCode.FAIL.getMsg(), null);
        }
    } finally {
        try {
            String streamId = streamInfo != null ? streamInfo.getStream() : null;
            log.info("[截图] 停止临时流 {}/{} stream={}", deviceId, channelId, streamId);
            stop(InviteSessionType.PLAY, device, channel, streamId);
        } catch (Exception e) {
            log.warn("[截图] 停止临时流失败 {}/{}: {}", deviceId, channelId, e.getMessage());
        }
    }
});
```

### 修复点 2：`getSnap(CommonGBChannel, ErrorCallback<byte[]>)` line 1712-1738

国标级联场景同等修复，同样 `try/finally + stop`。

### 修复点 3：`snapOnPlay(...)` line 676-690（连带问题）

主修复上线后发现 ZLM 在截图完成 ~50ms 内又被自动重新拉起。定位为：`play()` 成功回调链中 `callback.run()` 在 `snapOnPlay()` 之前同步执行，`callback` 内主动 stop 流后，紧接着的 `snapOnPlay()` 调 ZLM `/index/api/getSnap`，ZLM 在流不存在时触发 `on_stream_not_found` → wvp `autoApplyPlay=true` 自动重建流。

修复为防御检查：

```java
private void snapOnPlay(MediaServer mediaServerItemInuse, String deviceId, String channelId, String stream) {
    // 防御检查：若上游 callback（如 getSnap 临时流借用）已主动 stop 流，跳过默认封面生成。
    InviteInfo inviteInfo = inviteStreamService.getInviteInfoByStream(InviteSessionType.PLAY, stream);
    if (inviteInfo == null || inviteInfo.getStreamInfo() == null) {
        log.info("[默认封面] 流已不存活，跳过截图避免触发自动重建: {}/{} stream={}", deviceId, channelId, stream);
        return;
    }
    String path = "snap";
    String fileName = deviceId + "_" + channelId + ".jpg";
    log.info("[请求截图]: " + fileName);
    mediaServerService.getSnap(mediaServerItemInuse, MediaStreamUtil.RTP_APP, stream, 15, 1, path, fileName);
}
```

## 五、回归验证

### 单次截图（成功路径）

```text
19:11:43.553  [点播开始]   SSRC: 0200005252
19:11:44.390  [ZLM HOOK] 流注册
19:11:44.544  [请求截图-临时流]   ..._20260524191143.jpg     ← 修复点 1
19:11:46.352  [截图] 停止临时流   stream=350200..._05331    ← 修复点 1 finally
19:11:46.394  [停止点播/回放/下载] 成功                      ← BYE 已发
19:11:46.529  [ZLM HOOK] 流注销
19:11:46.533  [点播成功]                                    ← play 回调链滞后
19:11:46.535  [默认封面] 流已不存活，跳过截图避免触发自动重建  ← 修复点 3 ✓
                  （后续无任何「流未找到」/「发起自动点播」）
```

### 失败路径（设备 486 Busy Here）

```text
18:51:59.146  [点播开始]
18:52:00.149  [点播失败] 486:Busy Here
18:52:00.181  [截图] 停止临时流  stream=null         ← finally 兜底依然执行
18:52:00.181  [停止点播/回放/下载]
```

### 风暴测试（5 次连续截图）

```text
[18:53:27]  #1  code=0
[18:53:34]  #2  code=0
[18:53:38]  #3  code=0
[18:53:41]  #4  code=0
[18:53:45]  #5  code=0
+5s 后 ZLM 流数：0  ✅
```

### 资源占用对比

| 场景 | 修复前 | 第 1+2 修复点上线后 | 完整修复后（含修复点 3）|
|------|--------|------------------------|------------------------------|
| 1 次截图后立即查 ZLM | 1 流，~20s 释放 | 1 流（autoApplyPlay 重建），~16s 释放 | **0 流** |
| 5 次截图后立即查 ZLM | 5 流叠加，~20s 释放 | 1 流，~16s 释放 | **0 流** |

## 六、不变事项

- ❌ HTTP API 签名不变
- ❌ 前端无需改动
- ❌ 数据库无变更
- ❌ Redis 数据结构无变更
- ❌ wvp 内部 Java 接口签名无变更
- ✅ 仅 `PlayServiceImpl.java` 一个文件改动，约 50 行

## 七、设计决策记录

| 决策点 | 选择 | 理由 |
|--------|------|------|
| `try/finally` vs 仅成功分支 stop | try/finally | 截图异常 / 文件不存在等所有路径都停流 |
| 停流方法 | `stop(InviteSessionType.PLAY, device, channel, streamId)` | 完整链路：BYE + removeInviteInfo + stopPlay + closeRTPServer，复用现成方法 |
| 递归 vs 原地截图 | 原地 | 递归会再走「已存在流分支」逻辑，徒增混乱；直接用回调返回的 StreamInfo 即可 |
| stop 失败处理 | warn 不抛 | 截图本身已成功，停流失败属次要问题，不应吞掉成功结果 |
| 修复点 3 的判断口径 | `InviteInfo == null \|\| streamInfo == null` | 与「借用流场景」精确等价（正常用户点播 callback 不会主动 stop 流），且**集群安全**（走 Redis 共享） |

## 八、未采纳的方案

| 方案 | 否决理由 |
|------|----------|
| 调小 ZLM `streamNoneReaderDelayMS` | 治标不治本；`stream-on-demand=false` 时无效 |
| 引入「流借用引用计数」（InviteInfo 增 consumers 字段） | 1-2 周工作量；当前无第二个借用流场景，过度设计 |
| 把 `snapOnPlay` 调用顺序提前到 `callback.run` 之前 | snapOnPlay 是同步阻塞 HTTP，会让 callback 延迟 ~1-2s，破坏现有时序 |
| ThreadLocal/进程内 Map 标记「借用流」 | 集群部署时 wvp 跨实例 RPC（`redisRpcPlayService.play`），本地标记不可见；当前补丁通过 Redis 中的 InviteInfo 走集群共享，反而更安全 |
| 关闭 `autoApplyPlay` | autoApplyPlay 是全局有用功能（用户拉流时自动建流），关掉副作用大 |

## 九、未在本次修复范围内（独立议题）

- `RedisRpcPlayServiceImpl.getSnap` 跨 wvp 实例 RPC 路径未审查
- 单元/集成测试覆盖未补全
- `IPlayService.play` 的 javadoc 未明确 callback 内可 stop 流的契约

建议在下个迭代视情况跟进。

## 十、影响代码与文件

| 文件 | 改动行数 |
|------|----------|
| `src/main/java/com/genersoft/iot/vmp/gb28181/service/impl/PlayServiceImpl.java` | ~50 行（3 处） |

## 参考

- 主 bug 入口调用方:
  - `@/d:/JXT/jxt-evidence-system/wvp-GB28181-pro/src/main/java/com/genersoft/iot/vmp/service/impl/AlarmServiceImpl.java`:168 `getSnapByAlarm`
  - `@/d:/JXT/jxt-evidence-system/wvp-GB28181-pro/src/main/java/com/genersoft/iot/vmp/gb28181/controller/PlayController.java`:264 `/api/play/snap`
- 现成 stop 方法: `@/d:/JXT/jxt-evidence-system/wvp-GB28181-pro/src/main/java/com/genersoft/iot/vmp/gb28181/service/impl/PlayServiceImpl.java`:1742
- ZLM `on_stream_none_reader` 兜底: `@/d:/JXT/jxt-evidence-system/wvp-GB28181-pro/src/main/java/com/genersoft/iot/vmp/gb28181/service/impl/SourceOtherServiceForGbImpl.java`:29
- 默认 streamOnDemand=true: `@/d:/JXT/jxt-evidence-system/wvp-GB28181-pro/src/main/java/com/genersoft/iot/vmp/conf/UserSetting.java`:89
