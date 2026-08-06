# 案例：语音喊话/对讲三处代码级修复（会话生命周期 + 链路地址 + 编码协商）

> 创建时间：2026-07-01
> 影响范围：Docker NAT 部署下的语音喊话（Broadcast）/ 对讲（Talk）
> 涉及组件：`PlayServiceImpl.java`、`InviteRequestProcessor.java`
> 关联文档：`case-2026-06-30-broadcast-message-timeout.md`、`case-2026-06-30-broadcast-rtp-nat-address.md`

---

## 背景

"设备收不到音频 / 设备 INVITE 被拒" 这一表象下实际叠了三层独立的代码缺陷。前两份 case 文档分别记录了 MESSAGE 超时和 SDP 地址错误的现场与初步修复；本文汇总三处**代码级**修复，作为彻底方案。三处彼此独立，可分别回滚。

---

## 修复一：广播会话生命周期与 MESSAGE 回复解耦

**文件**：`PlayServiceImpl.java` → `audioBroadcastCmd`

**缺陷**：原代码只在 MESSAGE 收到 200 OK 的 `okEvent` 回调里创建 `AudioBroadcastCatch`。慢设备（TonMX/SENTER 约 2.6~4s 才回 200 OK）会先撞上 `sip.timeout`（旧运行 jar 实际为 1000ms）→ `errorEvent` → `stopAudioBroadcast`。等设备随后发来 INVITE 时 catch 已不存在，`InviteRequestProcessor` 判 "非语音广播，已忽略" → FORBIDDEN。

```
41.801  MESSAGE 发出                 ← timer 计时
42.794  timeout(1000ms) → stopAudio  ← catch 从未创建
45.605  设备 INVITE 到达             ← catch=null，被判"非语音广播"忽略
```

**修复**：发 MESSAGE **之前**就创建 catch 并启动 10s "等待 INVITE" 定时器。

```java
// 1) 先建 catch + 启动 10s 等待 INVITE 定时器（兜底清理）
AudioBroadcastCatch audioBroadcastCatch = new AudioBroadcastCatch(...);
audioBroadcastManager.update(audioBroadcastCatch);
dynamicTask.startDelay(key, ()->{
    event.call("语音广播等待设备INVITE超时");
    stopAudioBroadcast(device, deviceChannel);
}, 10*1000);

// 2) 再发通知
cmder.audioBroadcastCmd(device, deviceChannel.getDeviceId(),
    eventResultForOk -> {
        // MESSAGE 200 OK：仅推进状态（带 null 守卫，处理"定时器先超时、200 OK 晚到"竞态）
        AudioBroadcastCatch catchForOk = audioBroadcastManager.get(deviceChannel.getId());
        if (catchForOk != null) {
            catchForOk.setStatus(AudioBroadcastCatchStatus.WaiteInvite);
            audioBroadcastManager.update(catchForOk);
        }
    },
    eventResultForError -> {
        // MESSAGE 超时/错误：不再 stopAudioBroadcast，只记 warn，交由 10s 定时器兜底
        log.warn("[语音广播]发送通知未收到回复（仍继续等待设备INVITE）...");
    });
```

**效果**：设备 200 OK 快、慢、甚至不回，只要 INVITE 在 10s 内到达即正常发流。

---

## 修复二：RTP 目标地址改用链路实测地址

**文件**：`InviteRequestProcessor.java` → `inviteFromDeviceHandle`

**缺陷**：设备 INVITE 的 SDP `o=` 行可能指向 NAT 网关（如 `172.18.0.1`）；原代码直接 `sdp.getOrigin().getAddress()` 作 RTP 目标，ZLM 发到网关，设备收不到。

**初步修复**（见 `case-2026-06-30-broadcast-rtp-nat-address.md`）：用 `Via.getHost()` 覆盖。

**本次收敛**：`Via.getHost()` 是设备**自报**地址，设备侧 NAT 时仍是私有地址（不可达）。改复用 `SipUtils.getRemoteAddressFromRequest(request, false).getIp()`，回退链与设备注册地址解析完全一致：

```
Via received   （设备带 rport 时由 SIP 栈回填的真实源 IP；按 RFC 3581 及 NIST JAIN-SIP 实际行为，无 rport 时为 null）
  → getPeerPacketSourceAddress()   （传输层 socket 源地址；永远非空、不依赖 rport，设备侧 NAT 时即真实可路由地址）
    → request.getRemoteAddress()   （兜底）
```

**要点**：`received` 的触发由 rport 决定，**不能假设总有**（代码库 `SipUtils.java:189` 已为此写了 null 守卫）；传输层 peer 源地址才是 NAT 真值。当前 `192.168.0.62` 直连场景下该段为 no-op（peer 源 == SDP o=，两者相等不覆盖），新增覆盖的是"设备 behind NAT 且无 rport"这一 `getHost()` 救不回来的场景。

```java
try {
    String peerAddress = SipUtils.getRemoteAddressFromRequest(request, false).getIp();
    if (peerAddress != null && !peerAddress.isEmpty() && !peerAddress.equals(addressStr)) {
        log.info("[语音喊话] SDP o=({})与链路实测地址({})不一致，使用链路地址", addressStr, peerAddress);
        addressStr = peerAddress;
    }
} catch (Exception e) {
    log.warn("[语音喊话] 获取链路地址失败，使用SDP o=地址", e);
}
```

---

## 修复三：跟随设备首选编码协商（PS vs PCMA）

**文件**：`InviteRequestProcessor.java` → `inviteFromDeviceHandle` + `sendOk`

**缺陷**：原代码在广播 INVITE 应答里硬编码 `pt=8 / a=rtpmap:8 PCMA/8000/1`。部分设备在 `s=Play` 模式下要求 PS 封装（`m=audio` 首位为 `96/PS/90000`），收到裸 PCMA 走 PS 解封路径 → 解不出 → **无声**。

**修复**：

```java
// 1) 解析设备 INVITE 的 m=audio 行首个 payload（设备首选编码）
String preferredPayload = null;
Vector mediaFormats = media.getMediaFormats(false);
if (mediaFormats != null && !mediaFormats.isEmpty()) {
    preferredPayload = String.valueOf(mediaFormats.get(0));
}

// 2) 按首选决定封装方式
if ("96".equals(preferredPayload)) {        // PS/90000
    sendRtpItem.setPt(96);
    sendRtpItem.setUsePs(true);
} else {                                    // 裸 PCMA
    sendRtpItem.setPt(8);
    sendRtpItem.setUsePs(false);
}

// 3) 按实际选定编码生成应答 SDP（m= 行 + a=rtpmap）
int payloadType = sendRtpItem.getPt();
content.append("m=audio " + sendRtpItem.getLocalPort() + " "
        + (mediaTransmissionTCP ? "TCP/RTP/AVP" : "RTP/AVP") + " " + payloadType + "\r\n");
content.append(sendRtpItem.isUsePs()
        ? "a=rtpmap:" + payloadType + " PS/90000\r\n"
        : "a=rtpmap:" + payloadType + " PCMA/8000/1\r\n");
```

**注意**：硬编码假设 `96 == PS`（GB28181 国标语境下成立）。若将来遇到某设备在 96 上挂了其他编码（如 G.722.1），需再加一层 `a=rtpmap` 解析判断，否则会误判为 PS。

---

## 验证

- `mvn -o compile` → `BUILD SUCCESS`（Adoptium JDK 21，零编译错误）
- 三处均为源码改动，**需重新编译并更新运行 jar** 方可生效（重建 wvp 镜像，或按离线部署习惯 `docker cp` 新 jar）；改挂载卷配置对本组补丁无效——这正是历史上反复踩过的"源码改了但运行 jar 是旧的"坑

---

## 经验总结

1. **广播会话的生命周期不应绑定在某一条信令的回复时机上。** MESSAGE 200 OK 与设备 INVITE 是两个独立 SIP 事务，时序无保证（可先于、迟于、甚至独立发生）；乐观建会话 + 统一定时器兜底，比"成功才建"健壮得多。

2. **SIP NAT 真值有层级，要用最不易撒谎的那一层。** 设备自报（SDP `o=`、Via sent-by）< 栈观测（Via `received`，但 **rport 依赖**）< 传输层 peer 源地址（`getPeerPacketSourceAddress`，永远可得）。别假设 `received` 总在——它由 rport 触发，无 rport 即为 null。

3. **GB28181 编码协商不能想当然。** 硬编码 PCMA 在国标 `s=Play` 语境下会丢掉一类 PS 设备；应跟随设备 `m=` 行首选，并按选定编码生成应答 SDP 的 `m=` 行与 `a=rtpmap`。

4. **"源码改了但 jar 是旧的"是最常见的无效修复。** 源码类改动必须落到运行 jar；配置/挂载卷改动只对配置类生效。改完务必确认运行的 jar 与源码一致。
