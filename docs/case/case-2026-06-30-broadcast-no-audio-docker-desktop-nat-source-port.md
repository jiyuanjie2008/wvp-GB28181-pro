# 案例：语音喊话信令全通但终端无声——多疑点排查（编码封装 / RTP 打包过大 / NAT）

> 创建时间：2026-06-30（2026-06-30 二次刷新：修正根因排序）
> 影响范围：Windows + Docker Desktop(WSL2) 部署下的 GB28181 语音喊话(Broadcast)/对讲(Talk)
> 涉及组件：`InviteRequestProcessor.java`、`PlayServiceImpl.java`、`application-docker.yml`、ZLMediaKit、Docker Desktop/WSL2 网络、终端设备(S917/SENTER, com.dcw.vochat)
> 关联案例：`case-2026-06-30-broadcast-rtp-nat-address.md`、`case-2026-06-30-send-port-missing-broadcast-no-audio.md`
>
> **状态：已解决/已定论（2026-07-09）。** 平台→终端下行音频**走语音广播(`s=Play`)可正常出声**（真机实测成功）；语音对讲(`s=Talk`)下行为固件层不支持。**以下方最新的"重要修正 5"为准**，其余历史修正 1–4 保留作排查轨迹。详见**第七节**、**第九节**、**第十节**及 `zx/GB28181_Protocol_Analysis.md` §11。
> **重要修正 4（2026-07-08）**：曾寄望"改用对讲(Talk)路径绕开坏掉的广播路径"（第 8.3 节）。对讲已实现为 UDP 主动推流并实测，**RTP 到达设备但症状与广播完全一致（socket 绑定、`rx_queue` 钉死、应用不读、`talkState=0`、无声）**。结论修正：**平台推给该设备的下行音频，无论广播/对讲、PS/PCMA、TCP/UDP，设备固件都不激活接收管线**——非广播路径独有 bug。
> **⭐ 重要修正 5（2026-07-09 决定性，推翻修正 4 的一半）**：通过反汇编 `libnative-lib.so` + 真机 `/sdcard/log.txt` 实测，**区分了广播与对讲**：
> - **语音广播 (`broadcastMode=true`, `s=Play`) 实测成功出声**：设备主动 INVITE，收到下行 RTP(`p_data:640`)→`sip_audio_cb s_name=[Play]`→`Broadcast_StreamDown`→`funCallbackJavaFromJNIBroadcastCallBack`→`playPCM`。**广播接收管线可以激活**（此前 07-02 "广播不激活"的判断是因为当时 RTP 未真正正确到达设备端口/地址，属环境问题，非固件不支持）。
>   - **✅ 人耳实测复核（2026-07-09 13:06）**：向 `broadcast` 流推 600Hz PCMA 测试音，**设备扬声器实际播出清晰稳定的 600Hz 持续音**（用户现场确认）。证据等级从"日志显示已调用 `playPCM`"升级为"**真机扬声器可闻**"，广播喊话端到端完全打通。
> - **语音对讲 (`s=Talk`) 确实固件层不支持下行**：`suas_cs_2xx_sent` 中 `talk_rx=bit11` 恒为 0（全库该位只有 `bic` 清除、无任何 `orr` 置位），只启动上行 `talkUP_startStream`。
>
> **⭐ 重要修正 6（2026-07-09 补充，决定"喊话"能否出声的真正变量是 TCP/UDP）**：终端"广播传输配置"改 UDP 并**重启**后复测（详见第十二节）：
> - **配置需重启生效**：重启后设备广播 SDP 由 `TCP/RTP/AVP`(`a=setup:active`) 变为 `RTP/AVP`(UDP)。
> - **UDP 下无声，但包确实到了设备**：`/proc/net/udp` 端口 49002 `rx_queue` 钉死在 `0x34100`(213760B)、`drops` 197→214 持续增长 → **UDP 包到达设备内核，但固件 app 层从不 `recv()`**。（纠正过程中一度误判为"UDP 到不了设备/Docker NAT 挡住"，`/proc/net/udp` 已证伪。）
> - **真正判据是 TCP/UDP，不是 broadcast/talk**：TCP 广播（设备 `setup:active` 主动拨出）app 会读→出声；UDP（广播或对讲）app 都不消费→无声。**故喊话须保持 TCP。**
>
> **最终定论：平台→终端下行音频走"语音广播(`s=Play`)+TCP(设备主动拨出)"可出声；改 UDP 则包到内核但 app 不消费→无声；对讲(`s=Talk`)下行不可用。完整 native 逆向与实测证据见 `zx/GB28181_Protocol_Analysis.md` §11 及本文第十二节。**
> **重要修正 1**：初版把"Docker NAT 改写源端口"当作根因；经重新分析日志（设备 ACK 正常、To-tag 匹配、源 IP 匹配），NAT 已**降级为低概率**。
> **重要修正 2（联网调研后）**：找到与本案 SDP 逐字吻合的权威资料，**疑点 A（编码封装）升为首要根因**——设备走 `s=Play`+PS/96 模式，WVP 却硬编码回裸 PCMA。已据此实现"应答跟随设备首选编码"的代码改动（见第六节），待重建镜像验证。
> **重要修正 3（2026-07-02 实测，见第七节）**：疑点 A（编码）、B（ptime）、C（NAT）**均已从"根因"降级**。IP 地址补丁重建生效后 RTP 已实测到达真机 `192.168.0.62`，编码也已回 PS/96，但**终端仍无声**。用 `/proc/net/udp` 抓到决定性证据：**终端绑定了 49002 但接收缓冲区(`rx_queue`)钉死在上限不排空 → 终端应用层从不 `recv()` → 广播接收/解码线程根本没启动**。根因收敛到**终端固件未激活广播接收管线**，非服务端问题。

---

## 一、问题现象

点击"喊话"后：

- ✅ 浏览器麦克风音频成功 WebRTC 推流到 ZLM（`broadcast/{deviceId}_{channelId}` 注册成功）
- ✅ WVP 向设备发送 SIP Broadcast 通知 MESSAGE
- ✅ 设备回复 INVITE，WVP 回 200 OK，ZLM `startSendRtp` 返回 code=0
- ✅ ZLM 持续向设备发送 RTP 音频包
- ❌ **终端始终没有声音**

是"信令全通、媒体也在发，但终端不出声"的典型表现。

---

## 二、排查过程与逐层定位

本次问题是**多个故障点叠加**，按修复顺序逐层暴露：

### 故障点 1：发流目标地址错误（已由 Via 补丁修复）

设备注册经 Docker NAT，INVITE 的 SDP `o=`/`c=` 和 Contact 都填成了 NAT 网关地址（`172.18.0.1`），真实 LAN IP（`192.168.0.62`）仅出现在 Via 的 sent-by。

旧镜像未带补丁时，ZLM 把音频发往 `172.18.0.1:49002`，到不了设备。

**修复**：`InviteRequestProcessor.java` 用 Via 头 host 覆盖 SDP `o=` 地址（详见 `case-2026-06-30-broadcast-rtp-nat-address.md`）。注意该补丁需**重新构建 wvp 镜像**才生效——曾出现源码已改但运行 jar 是旧的的情况。

```java
String addressStr = sdp.getOrigin().getAddress();
ViaHeader viaHeader = (ViaHeader) request.getHeader(ViaHeader.NAME);
if (viaHeader != null) {
    String viaHost = viaHeader.getHost();
    if (!viaHost.equals(addressStr)) {
        log.info("[语音喊话] SDP o= 行地址({})与Via头地址({})不一致，使用Via地址", addressStr, viaHost);
        addressStr = viaHost; // 设备真实 LAN IP
    }
}
```

### 故障点 2：SIP 超时过短导致广播会话被提前拆除（已修复）

终端(TonMXAndroid/SENTER)对 Broadcast 通知的 **200 OK 回复较慢（约 2.6~4 秒）**，而 `sip.timeout` 默认 **1000ms**。

`PlayServiceImpl.audioBroadcastCmd` 只在 MESSAGE 收到 200 OK 的 `okEvent` 回调里创建 `AudioBroadcastCatch`；1 秒超时后走 `errorEvent` → `stopAudioBroadcast`，把广播会话拆掉。等设备 4 秒后真正发来 INVITE 时，`audioBroadcastManager.get(channelId)` 已为 null，被 `InviteRequestProcessor:475` 判为"非语音广播，已忽略"，**根本不发流**。

关键日志：
```
15:10:41.801 done sending MESSAGE to hop 172.x.x.x
15:10:42.794 ERROR PlayServiceImpl:1314 语音广播发送失败：...:消息超时未回复
15:10:42.796 PlayServiceImpl:1341 [停止对讲] ...           ← 会话被拆
15:10:45.605 SIP/2.0 200 OK (Found Transaction null)       ← 设备200 OK迟到3.8秒
15:10:45.654 InviteRequestProcessor:475 非语音广播，已忽略  ← INVITE被拒
```

**修复 1（兜底，已做）**：`docker/wvp/wvp/application-docker.yml` 把 `sip.timeout` 由 `1000` 调大到 `5000`（挂载卷配置，重启 wvp 即可，无需重建镜像）。

**修复 2（根治，已实施）**：`PlayServiceImpl.audioBroadcastCmd` 改为**发送 MESSAGE 之前**即创建 `AudioBroadcastCatch` 并启动"10 秒等待 INVITE"定时器；
- `okEvent`（MESSAGE 收到 200 OK）仅把状态更新为 `WaiteInvite`，不再重建缓存；
- `errorEvent`（MESSAGE 超时/错误）**不再 `stopAudioBroadcast`**，只告警；
- 失败通知（`event.call`）移入定时器：仅当 10 秒内无 INVITE 才判失败并清理。

这样无论设备 MESSAGE 回复快慢、甚至不回复，只要 INVITE 到达时缓存存在即可正常发流，彻底解耦"MESSAGE 回复"与"INVITE 等待"两个独立 SIP 事务。需**重建 wvp 镜像**生效；与修复 1 互为双保险、不冲突。

### 修好前两点后：服务端已确认全部正确，但终端仍无声

详见第三节"服务端正确性逐条核对"。信令三次握手完整、ZLM 持续向正确地址发真实音频包，但终端 `talkState` 始终为 0、无声。问题已收敛到**媒体面**。

终端侧 adb logcat（com.dcw.vochat / Gb28181Local）佐证：
```
E/Gb28181Local: onStateUpdate sysState:2,connState:2,liveState:2,talkState:0,...
```
握手全程 **`talkState` 始终为 0**，无 `AudioTrack` 启动——终端建立了通话信令（甚至回了 ACK），却从未进入对讲播放态。

### 二次日志复盘：用数据排除的疑点

第二轮重新分析 wvp SIP 全量日志（一次捕获含 16:00 / 16:01 / 16:10 三次喊话），把以下疑点逐个排除：

| 疑点 | 证据 | 结论 |
|---|---|---|
| 200 OK 未送达设备 | 3 次 INVITE 用 3 个不同 branch、**无同事务重传** | 排除（设备收到了 200 OK，否则会重传 7 次） |
| To-tag / SDP 不匹配 | 设备 ACK 的 To-tag 与 wvp 200 OK 完全一致（如 `1782806402556`） | 排除（设备在 SIP 层接受了 PCMA 应答） |
| SSRC 不匹配 | 设备 INVITE 无 `y=`，wvp 顺序自增（1204/1205/1206），设备无 SSRC 预期 | 排除 |
| "Dialog does not exist" | wvp 对设备主动 INVITE 不维护服务端 dialog，ACK 仍被 `deliverEvent` 投递 | 排除（良性，不影响推流） |
| ZLM 没发 / 发错地址 | code=0、持续发往 `192.168.0.62:49002`、容器 ping 通 0% 丢包 | 排除 |

> ⚠️ 初版称"未见设备 ACK"是**错误**的：设备确实发了 ACK 且 To-tag 精确匹配。这条修正同时削弱了"设备在信令层因编码不符而拒绝"的猜测——它没拒，它 ACK 了；若编码是问题，发生在**解码/渲染层**而非信令层。

### 当前三个并列疑点（按可疑度排序）

**疑点 A · 编码封装（设备走 s=Play/PS 模式，wvp 硬编码裸 PCMA）★ 首要根因**

设备 INVITE 提供 `s=Play` + `m=audio 49002 RTP/AVP 96 8`，把 **PS/96 排在第一**（首选），PCMA/8 次选：
```
s=Play
m=audio 49002 RTP/AVP 96 8
a=rtpmap:96 PS/90000
a=recvonly
a=rtpmap:8 PCMA/8000
```
而 wvp 在 `InviteRequestProcessor` **无视设备 offer、硬编码回裸 PCMA/8**：

```java
// InviteRequestProcessor.java:604-605
sendRtpItem.setPt(8);
sendRtpItem.setUsePs(false);
// InviteRequestProcessor.java:649,652  (sendOk 应答 SDP 也写死)
//   m=audio <port> RTP/AVP 8
//   a=rtpmap:8 PCMA/8000/1
```

**权威依据（联网调研，腾讯云《Android GB28181 跨网段语音对讲》）**：国标设备收音频有两种模式，由 SDP `s=` 与首选编码决定：

| 模式 | SDP 特征 | 封装 |
|---|---|---|
| 模式1 `s=Talk` | `m=audio … RTP/AVP 8` + `a=rtpmap:8 PCMA/8000` | 裸 PCMA over RTP |
| 模式2 `s=Play` | `m=audio … RTP/AVP 96` + `a=rtpmap:96 PS/90000` | PS 封装（纯音频也打 PS）|

原文明确：**"如果 SDP 信息中 `s=Play`，那么对应的 200 OK 响应中的 SDP 也需要确保是 Play 模式。"** 另有多篇资料（CSDN《GB28181 PS 打包纯音频》等）指出：**语音广播/对讲场景中，纯音频也常用 PS 封装**，因为多数国标设备音视频统一走 PS 解封路径。

**对照本案**：设备明确是 `s=Play`+PS/96 模式，wvp 却回裸 PCMA/8 → **模式不匹配**，设备 PS 解封路径吃不进裸 PCMA → 无声。设备虽 ACK（SIP 层宽松接受），渲染层仍按 PS 处理。可行性已确认：ZLM 支持纯音频 PS 封装（issue #1891 中 `use_ps=1&pt=96&only_audio=1` 时 ZLM 打印 `PSEncoderImp`）。

**终端固件实证（2026-07-01，分析 `zx/` 终端反编译源码 + `libnative-lib.so`）——根因从"旁证"升级为"设备本体坐实"**：

终端为 `com.dcw.vochat`，GB28181 SIP/RTP 在 native `libnative-lib.so`（带符号），Java 仅播 PCM。`.so` 内日志字面串直接暴露其能力协商按 **SIP 会话模式二选一**：
```
[[[sip_call_in]]] Play  cm_add_sua_cap PS        ← s=Play → PS 封装
[[[sip_call_in]]] Talk  cm_add_sua_cap G711A     ← s=Talk → 裸 G711A/PCMA
[[[sip_call_in]]] Talk  cm_add_sua_cap PCMA
[[sua_start_media]] ... sua_start_audio PS ... l_v_sdp[0].encoder==PS
sip_audio_cb Play encoder:%s / Talk PCMA -->server talk_StreamDown!
```
终端收音频处理链：`[native PS解封+解码] → fromJNIBroadcastCallBackFun → onReceiveBroadcastAudioData → DCWAudioManager.playPCM(pcm)`。**封装判定全在 native，Java 不参与**。

结论：本案设备 `s=Play` → 走 PS 解封路径，WVP 回裸 PCMA 必然无声；WVP 改回 PS/96 与终端固件实现一致，方向正确。

**两点修正**：
1. **`talkState=0` 不是广播失败证据**：固件里广播(`Broadcast_StreamDown`)与对讲(`talkState`)是两条独立路径，广播不一定改 `talkState`；真正成败信号是 `onReceiveBroadcastAudioData` 是否被回调。
2. 终端自带 `g711a_decode`（能解裸 PCMA），但**仅在 `s=Talk` 模式**走该路径；WVP 广播是 `s=Play`，故走 PS 才是匹配解。

> 这是当前**最有力**的根因解释：有"设备 SDP 模式=Play/PS"的硬信号 + 权威资料逐字吻合。已据此实现代码改动（第六节）。

**疑点 B · RTP 打包过大（ptime≈100ms，远超常规 20ms）**

抓包显示出向 RTP 包 **812 字节 / 包、~10 pps**。G.711A @ 8kHz 下 800 字节负载 = **100ms** 音频。而 GB28181 终端绝大多数按 **20ms（160 字节负载、50pps）** 收音频。两边 SDP **均未协商 `a=ptime`**，ZLM 用了默认大颗粒打包。后果：终端 RTP/抖动缓冲按小帧设计，收到 800 字节负载可能直接丢弃或无法解析 → 不出声。

此疑点的特点：**即使编码正确，光打包颗粒过大也足以让终端不出声**，与"设备 ACK 了却无声"高度吻合，且与疑点 A **互不排斥**。

**疑点 C · Docker Desktop(WSL2) 双层 NAT 改写 RTP 源端口（已降级为低概率）**

环境 **Windows 11 24H2 + Docker Desktop(WSL2)** 存在两层 SNAT：
```
ZLM容器 172.18.0.x:50502/50506
   │ ① docker bridge MASQUERADE
WSL2(docker-desktop)
   │ ② WSL2→Windows NAT
Windows主机 192.168.0.40   ← 设备看到的源IP(容器ping设备 ttl=63)
   │ LAN
终端 192.168.0.62:49002
```
WVP 在 SDP 告知音频源 `c=192.168.0.40`。**降级原因**：经两层 NAT 后**源 IP 仍是 192.168.0.40，与 SDP 一致**；设备 ACK/握手均正常；且多数终端只校验源 IP、不校验源端口。该理论要成立必须假设终端做**严格对称 RTP（校验源端口）**，属较强且不常见的假设。仅当 A、B 都被排除后才回头验证。

> 对比：视频预览正常，是因为那是设备**主动推流入** ZLM（入向，端口映射可达）；喊话是 ZLM **主动发往**设备（出向）。出入向在 Docker Desktop 下行为不同——这是 NAT 理论残留的合理内核。

---

## 三、服务端正确性逐条核对（以 SSRC=0200001204 那次为例）

| 报文 | 方向 | 关键内容 | 结论 |
|---|---|---|---|
| Broadcast MESSAGE | wvp→设备 | CmdType=Broadcast, SourceID=平台, TargetID=设备 | ✅ |
| 200 OK (MESSAGE) | 设备→wvp | 迟到约 3 秒，5000ms 超时内被接受 | ✅ |
| INVITE (SDP offer) | 设备→wvp | `c=172.18.0.1`(NAT误填) `m=audio 49002` `a=recvonly` `PCMA/8000` | 设备侧地址错误 |
| 地址纠正 | wvp 内部 | Via host 192.168.0.62 覆盖 o= → 目标 192.168.0.62:49002 | ✅ |
| 200 OK (SDP answer) | wvp→设备 | `c=192.168.0.40` `m=audio 50506` `a=rtpmap:8 PCMA/8000/1` `a=sendonly` `y=0200001204` `f=v/////a/1/8/1` | ✅ 规范 |
| startSendRtp | wvp→ZLM | `is_udp=1 pt=8 ssrc=0200001204 dst_url=192.168.0.62 src_port=50506 dst_port=49002 only_audio=1`，code=0 | ✅ 与 SDP 自洽 |

服务端每一项均规范且互相自洽：

- **编码可解析**：PCMA(pt8) 是设备 offer 的**次选**编码（首选为 PS/96，见疑点 A）；双方就 pt8 本身一致。
- **方向匹配**：设备 `a=recvonly` ↔ 平台 `a=sendonly`。
- **SSRC 一致**：SDP `y=0200001204` = ZLM 发流 ssrc。
- **端口一致**：SDP `m=audio 50506` = ZLM `src_port=50506`。
- **目标正确**：dst=设备真实 IP `192.168.0.62:49002`。
- **网络可达**：ZLM 容器 `ping 192.168.0.62` 通（ttl=63，0% 丢包），tcpdump 见 RTP 持续 `Out` 发往设备。

> 设备**有回 ACK**且 To-tag 与 200 OK 一致；`BroadcastPushAfterAck=false`，回 200 OK 后立即推流，不依赖 ACK。

**结论：服务端（WVP+ZLM）的信令与发流逻辑完全正确、自洽。** 唯一可商榷处是 wvp **未遵循设备 SDP 的编码优先级**（硬编码 PCMA/8），以及**未协商/控制 ptime**——这正是当前疑点 A、B 的来源。无声根因落在媒体的"终端能否消费"环节，而非服务端信令逻辑。

---

## 四、验证计划（逐变量隔离，一次只改一处）

原则：三个疑点互不排斥，**每次只改一个变量、其余不动**，再发一次喊话观察是否出声，避免"一次改两处分不清谁起作用"。

### 实验 1（首选，代码已实现，见第六节）：应答跟随设备首选编码 PS/96

`InviteRequestProcessor` 改为按设备 INVITE 首选编码动态应答——offer 首位是 96 就回 `use_ps=1 / pt=96` 且 SDP `m=audio … RTP/AVP 96` + `a=rtpmap:96 PS/90000`；首位是 8 则维持裸 PCMA/8。需**重建 wvp 镜像**后测试。
- **出声** → 根因坐实为**疑点 A（编码封装）**，结案。
- **仍无声** → 排除 A，进入实验 2。

### 实验 2：把 ptime 从 100ms 降到 20ms

先抓包确认每包确为 812 字节/100ms（命令见下），再研究 ZLM G711 打包颗粒是否可调，降到 20ms（160 字节负载、50pps）再发。
```pwsh
docker exec docker-polaris-media-1 sh -c "timeout 40 tcpdump -n -tt -c 400 'udp and dst host 192.168.0.62' 2>/dev/null"
```
判读：`UDP, length` ≈ 812 → 100ms 成立；≈ 172 → 20ms（疑点 B 排除）。
- **出声** → 根因坐实为**疑点 B（打包过大）**，结案。
- **仍无声** → 排除 B，进入实验 3。

### 实验 3：消除 NAT（验证疑点 C）

二选一：
1. **WSL2 镜像网络**：新建 `%USERPROFILE%\.wslconfig`：
   ```ini
   [wsl2]
   networkingMode=mirrored
   ```
   `wsl --shutdown` 并重启 Docker Desktop。去掉 WSL2 一层 NAT。
2. **Linux 主机 + host 网络**（生产级最稳）：wvp+zlm 部署到 LAN 上 Linux 主机，`network_mode: host`，完全无 NAT，设备收到的源与 SDP 严格一致。

> 实验 1（编码）优先级最高：有权威依据 + 设备 SDP 硬信号；实验 1/2 在当前 Windows + Docker 环境即可做，无需动网络。

---

## 五、经验总结

1. **"信令通"≠"媒体通"**：本案 SIP 三次握手（含 ACK）全通、ZLM 持续发真实音频包，终端仍无声。媒体面必须单独验证（抓包确认源/目的、SSRC、编码、**打包时长 ptime**、方向）。

2. **不要把"未排除"当成"已坐实"**：初版仓促把 NAT 定为根因。重新读日志后发现设备 ACK 正常、To-tag 匹配、源 IP 匹配，NAT 概率其实很低。**结论要随证据更新，敢于推翻自己。**

3. **尊重设备 SDP 的编码优先级**：设备把 PS/96 排在 PCMA/8 之前，wvp 却硬编码回裸 PCMA（`InviteRequestProcessor:604-605/649-652`），违背了 offer/answer 应遵循对端首选的原则——这是疑点 A 的根源。

4. **音频打包时长（ptime）是易被忽视的媒体面变量**：812 字节/包 = 100ms G.711A，是常规 20ms 的 5 倍；双方均未协商 `a=ptime`。大颗粒打包可能让按小帧设计的终端直接丢弃 RTP。

5. **善用终端 adb logcat 定位媒体面**：`talkState` 是否切换、是否打开 `AudioTrack`，能区分"服务端没发对"还是"终端没收/没播"。终端为 Android（S917/SENTER, com.dcw.vochat），`adb logcat -s Gb28181Local System.out` 可观察国标状态机。

6. **慢设备需放宽 `sip.timeout`**：部分终端对 MESSAGE 的 200 OK 回复慢达数秒，默认 1000ms 会误判超时并拆除广播会话（疑点已修，见故障点 2）。

7. **改了源码要确认运行的是新 jar**：`docker/wvp/Dockerfile` 在容器内 `mvn package` 构建；源码补丁需重新 `docker compose build`/重建镜像才生效，否则现象与未改一致。

---

## 六、代码改动：应答跟随设备首选编码（验证疑点 A）

文件：`InviteRequestProcessor.java`（语音广播 INVITE 处理）。

**改动思路**：解析设备 INVITE 的 `m=audio` 媒体格式列表，取**首位负载类型**作为设备首选；
- 首位为 `96`（PS）→ `setUsePs(true)` / `setPt(96)`，`sendOk()` 应答 SDP 用 `m=audio … RTP/AVP 96` + `a=rtpmap:96 PS/90000`；
- 否则维持裸 PCMA：`setUsePs(false)` / `setPt(8)`，`a=rtpmap:8 PCMA/8000/1`。

不再无条件硬编码 PCMA/8，从而遵循 GB28181 "s=Play 模式须回 PS" 的约定。

**验证方式（明天有设备时）**：
1. 重建 wvp 镜像：`docker compose build polaris-wvp`（或对应服务名）并重启。
2. 发起喊话，观察终端是否出声；同时 `adb logcat -s Gb28181Local` 看 `talkState` 是否从 0 切换。
3. 出声 → 疑点 A 坐实、结案；仍无声 → 回退此改动，转实验 2（ptime）。

> 回退方式：恢复 `setPt(8)`/`setUsePs(false)` 与 `sendOk()` 固定 PCMA 应答即可。

---

## 七、2026-07-02 决定性复盘：根因锁定在终端固件（非服务端）

当天在**真机在线**条件下，把服务端三大疑点逐一做成"改一处→重建→实测"的对照实验，最终用终端内核 socket 状态锁定根因。

### 7.1 IP 地址补丁重建生效，RTP 实测到达真机

**关键新发现**：连设备**注册存的 IP 都是 `172.18.0.1`**（`GET /api/device/query/devices/...` 返回 `"ip":"172.18.0.1"`），只有 INVITE 的 **Via sent-by host** 和 `sipTransactionInfo.callId` 里才保留真实 `192.168.0.62`。因此 `device.getIp()` 不可用，**唯一可靠来源是 Via sent-by host**。

> 为何 SIP 信令能通、RTP 不通？——SIP 是设备**主动发包**先建立了 Docker conntrack 映射，WVP 回 `172.18.0.1:43903` 靠这条映射反向 NAT 回设备（60s 心跳保活）；而 RTP 是**全新 UDP 流**打到 `172.18.0.1:49002`，**无 conntrack 映射**，直接死在网关。打到 `192.168.0.62`（出站 masquerade）才可达。

据此把 `InviteRequestProcessor` 广播 RTP 目标解析改为 **Via sent-by host**（见 7.4），重建镜像后实测：

```
# ZLM 容器 tcpdump（会话建立即刻，无任何手动重定向）
12:06:11 eth0 Out IP 172.18.0.3.50504 > 192.168.0.62.49002: UDP, length 200  (×50/s)
```

**RTP 从会话建立起就直接、稳定发往真机 `192.168.0.62:49002`**。IP 问题彻底解决。

### 7.2 三大疑点全部被实测排除

| 疑点 | 实验条件 | 结果 | 结论 |
|---|---|---|---|
| **C · NAT/地址** | Via 补丁重建，tcpdump 实证 RTP 到达 192.168.0.62 | 仍无声 | **排除**（地址已对，是真 bug 已修） |
| **A · 编码封装** | 代码已回 PS/96（use_ps=1,pt=96），与终端固件 `s=Play→PS` 一致 | 仍无声 | **排除**为无声主因 |
| **B · ptime/时序** | 全新会话 RTP 即时到达；亦排除"接收窗口过期" | 仍无声 | **排除** |
| **并发拉流** | `liveState:0`（无并发拉流）时反复喊话 | 仍无声 | **排除** |

### 7.3 决定性证据：终端绑定端口却从不读取（`rx_queue` 钉死）

终端无 `awk/ss`，直接读 `/proc/net/udp`（`49002 = 0xBF6A`）：

```
# 采样 x3，间隔 1s（RTP 正以 50pps 打到该端口）
12:06:36  874: 00000000:BF6A 00000000:0000 07 00000000:00034100 ... inode=50834
12:06:37  874: 00000000:BF6A 00000000:0000 07 00000000:00034100 ...
12:06:38  874: 00000000:BF6A 00000000:0000 07 00000000:00034100 ...
```

判读：
- `00000000:BF6A` = 终端在 `0.0.0.0:49002` **绑定了 UDP socket**（本轮新建，inode 变化）；
- 远端 `00000000:0000` = **未 connect**，接收不做源地址过滤；
- 第 4 列 `tx:rx = 00000000:00034100` → **`rx_queue = 0x34100 = 213760 字节，恒定在接收缓冲区上限不排空**。

**若应用在 `recv()`，队列会边读边排空、数值波动；现在秒满并钉死 = 终端应用层完全没有读取该 socket，内核缓冲填满后直接丢弃后续包。**

配套佐证：`talkState` 全程为 0；终端 logcat 从不出现 `设置广播...seq:` / `onReceiveBroadcastAudioData` / `playPCM`（该回调是**每收一包触发一次**的，完全不触发 = native 一个包都没消费）。

### 7.3.1 编码一致性实测：平台应答 = 设备 INVITE 首选编码（PS/96）

为排除"编码不匹配"这一残留疑点，直接从 WVP 日志逐字比对同一会话（`ssrc=0200001209`）的 offer/answer/发流参数：

**设备 INVITE（offer）——首选编码 PS/96**
```
s=Play
m=audio 49002 RTP/AVP 96 8      ← 96 排第一(首选)，8 次选
a=rtpmap:96 PS/90000
a=rtpmap:8 PCMA/8000
a=recvonly
y=0200001209
f=v/////a/1/8/1
```

**WVP 200 OK（answer）——回的就是 PS/96**
```
s=Play
m=audio 50504 RTP/AVP 96
a=rtpmap:96 PS/90000
a=sendonly
y=0200001209
f=v/////a/1/8/1
```

**ZLM 实际推流参数**
```
pt=96, use_ps=1, only_audio=1, ssrc=0200001209, dst_url=192.168.0.62, dst_port=49002, src_port=50504
```

逐项对齐：

| 项 | 设备 offer 首选 | WVP answer / ZLM 发流 | 一致? |
|---|---|---|---|
| 编码/PT | `96` = PS/90000 | `pt=96` / `PS/90000` | ✅ |
| 封装 | PS（`s=Play`） | `use_ps=1`，`s=Play` | ✅ |
| SSRC | `y=0200001209` | `y=0200001209` / `ssrc=0200001209` | ✅ |
| 方向 | `a=recvonly` | `a=sendonly` | ✅ |
| 端口 | 收 49002 | `dst_port=49002` | ✅ |

**结论**：平台应答编码 = 设备 INVITE 首选编码（**PS/96，s=Play 模式**），与终端固件 `s=Play → PS 解封` 路径也吻合。编码彻底排除为无声主因——协商完全一致、PS/96 也已发到真机，终端仍不读取 socket。

### 7.4 本次代码改动（IP 修复，已重建生效并保留）

文件：`InviteRequestProcessor.java`，广播 INVITE 处理中 RTP 目标地址解析。

- **改动**：由"`getRemoteAddressFromRequest(request,false)`（取 received/peer 源地址）"改为"**取 `ViaHeader.getHost()`（Via sent-by host）**"，失败或为空再回退 SDP `o=`。
- **理由**：Docker bridge + 同 LAN 部署下，received/peer/注册 IP/SDP 全是网桥网关（172.x），只有 Via sent-by 保留设备真实可达 LAN IP。
- **拓扑权衡（已在代码注释标注）**：本改动适用于"WVP 容器化 + 设备同 LAN"；若 WVP 在公网、设备在运营商 NAT 后（sent-by 为不可路由私网），则相反应取 Via received。**建议后续做成按部署拓扑可配置**。

### 7.5 结论与后续建议

**服务端（WVP + ZLM）的信令、地址、编码、发流已全部正确且经实测验证。** 无声根因落在终端侧，WVP 无法修复。

> **⚠️ 实测事实 vs 推断（边界声明）**
>
> 为避免把"强推断"误读为"已证死"，此处明确区分：
>
> | 类别 | 内容 | 依据 |
> |---|---|---|
> | **实测事实** | RTP 已稳定发到真机 `192.168.0.62:49002`（tcpdump，50pps） | 第 7.1 节 |
> | **实测事实** | 编码/SSRC/端口/方向逐字一致（PS/96，s=Play） | 第 7.3.1 节 |
> | **实测事实** | 终端**绑定了** 49002 UDP socket，但 `rx_queue` 钉死在缓冲上限不排空 | 第 7.3 节 `/proc/net/udp` |
> | **实测事实** | 终端 `onReceiveBroadcastAudioData`/`playPCM` 回调**从不触发**、`talkState` 全程 0 | 第 7.3 节 logcat |
> | **实测事实** | 终端广播 native API **只有一个下行回调、无任何 app 可调的启用方法** | 第 8.2 节反编译 |
> | **推断（强，未 100% 证死）** | 上述现象的**成因**是"终端固件 native 广播接收线程未启动/该路径是弱实现或 bug" | 由"socket 不被读取 + 回调从不触发"反推 |
>
> 换言之：**"终端收到 RTP 却不读取、不回调、不出声"是实测坐实的；"具体是 native 接收线程没起来"是最合理的推断，尚未通过动态调试 `.so` 或厂商确认做最终证死。** 无论成因细节如何，"服务端已正确、问题在终端固件的广播接收路径"这一定位不受影响。

尚未验证、按权威性排序的后续动作（用于把上述推断进一步证死）：

1. **改回裸 PCMA/8 测试**（WVP 侧，需重建）：终端 offer 同时给了 `PCMA/8`。虽然 `rx_queue` 不排空更像"接收线程未启动"而非"解码失败"，但若 native 的**接收线程启动与否是按协商编码 gate 的**，回 `pt=8/裸PCMA` 或能触发其接收线程。成本低、值得一试。
2. **抓厂商平台成功喊话的信令/SDP 对比**（最权威）：若厂商自带平台能让该终端广播出声，逐字对比 200 OK SDP（`s=`、`f=`、编码、`y=`、`a=`）与本案差异。
3. **联系终端厂商**：确认该型号是否支持平台侧**语音广播(B2)** 播放、其广播接收器要求的 SDP/编码/封装规格，以及是否需要设备端配置/固件开关。

### 7.6 本轮新增经验

8. **`/proc/net/udp` 的 `rx_queue` 是区分"终端没收到"与"终端收到不处理"的终极判据**：绑定存在 + `rx_queue` 钉死上限不排空 = 端口开了但应用不读，接收线程未启动；这比 logcat 更底层、更不可抵赖。
9. **"RTP 到达"必须用 tcpdump 在**目标可达路径**上实证**，不能只看 ZLM `startSendRtp` code=0（那只代表发送方在发）。
10. **容器化部署里，设备注册 IP 同样会被 Docker NAT 污染**（本案注册 IP=172.18.0.1）；跨主机/跨网段发流不能依赖 `device.getIp()`，Via sent-by host 才是设备自报的可达地址。
11. **逐变量隔离要"改一处→重建→只看一个信号"**：本案靠"tcpdump 确认到达 + `rx_queue` 确认不读"两个正交信号，才干净地把根因从服务端切到终端。

---

## 八、2026-07-02 终端代码分析：广播接收是 native 自治，无 app 启用钩子（方向 1 结论）

在确认根因在终端后，深入分析反编译源码（`D:\JXT\jxt-evidence-system\zx`，终端 App `com.dcw.vochat`），回答"终端为何绑定了端口却不读广播 socket"。

### 8.1 先否定"视频流干扰"假设（用户方向 2）

用户曾提出"是否终端上视频流(拉流)影响了喊话"。**用数据直接否定**：

- 本次整个 logcat 抓取期间（含 12:06 喊话），`onStateUpdate` 里 **`liveState` 全程为 0**、ZLM 上**无任何视频流** → 喊话时根本没有并发视频，仍无声。
- 早期 06-30 记录里 `liveState:2`（有视频）时也无声。

**有视频、无视频喊话都失败 → 视频流干扰假设证伪，前端分离喊话与拉流不会解决问题。**（唯一未证伪的反向假设"广播需先有视频会话"无任何证据支持。）

### 8.2 native API 全集：广播只有一个下行回调，无任何启用/初始化方法

`com.wind.ndk.camera.live.Gb28181`（`libnative-lib.so` 的 Java 包装）暴露的 GB28181 能力：

| 能力 | 上行(设备→平台) | 下行(平台→设备) | 初始化方法 |
|------|------|------|------|
| **对讲 Talk** | `talkUpInStream()` + `setTalkUpInitArgv()` | `fromJNItalkDownCallBackFun` → `onReceiveTalkAudioData` → `playPCM` | 有 |
| **广播 Broadcast** | 无 | `fromJNIBroadcastCallBackFun` → `onReceiveBroadcastAudioData` → `playPCM` | **无** |

广播相关**仅一个回调** `fromJNIBroadcastCallBackFun`（`Gb28181.java:112`，native 每收一包音频回调一次），**没有 `startBroadcast/enableBroadcast/acceptBroadcast` 之类任何 app 可调的启用方法**。

**关键结论**（区分实测与推断）：
1. **【实测】** 广播接收相关 native API **只有一个下行回调 `fromJNIBroadcastCallBackFun`，无任何 app 可调的启用/初始化方法** → 无声**不是"app 漏调了某个方法"**。
2. **【实测】** app 侧无 gate、socket 又实测不被读取（第 7.3 节 `rx_queue` 钉死、回调从不触发），故**问题落在闭源 `libnative-lib.so` 的广播接收路径内部**，非 app、非服务端。
3. **【推断，未 100% 证死】** 更具体的成因是"native 广播接收/解码线程未启动或该路径是弱实现/bug"——这是由"socket 不被读取 + 回调从不触发"反推得出的**最合理解释**，但尚未通过动态调试 `.so` 或厂商确认最终坐实。继续深挖只能动态调试 `.so`（成本极高）或找厂商。
4. **【实测】** Java 回调本身正常（`onReceiveBroadcastAudioData` 就一句 `playPCM`），若 native 肯回调必能出声——问题在 native 从不回调。

### 8.3 更有价值的路：改用「语音对讲(Talk)」绕开坏掉的广播路径

> **⚠️ 2026-07-08 更新：本节的乐观假设已被实测推翻。** 对讲(平台主动 INVITE, `s=Talk`/PCMA/UDP)已实现并实测，RTP 确认到达设备，但**设备表现出与广播完全一致的症状（socket 绑定、rx_queue 钉死、应用不读、`talkState=0`、无声）**。详见**第九节**。结论修正为：**平台推给设备的下行音频，无论广播还是对讲，该设备固件都不激活接收管线——不是广播路径独有的 bug。**

对比 8.2 表：**对讲(Talk)是一等公民、实现完整**（完整 `talkState` 状态机、上下行、初始化），下行同样落到 `playPCM`；广播只有孤立回调，明显是弱实现/疑似 bug。

`.so` 日志串证明 native 按 SIP 会话模式**走不同代码分支**：
```
[[[sip_call_in]]] Talk  cm_add_sua_cap G711A/PCMA   ← 平台主动 INVITE 设备，走对讲路径
[[[sip_call_in]]] Play  cm_add_sua_cap PS           ← 设备自己 INVITE，走广播路径(已坏)
```

**两条路径代码不同——广播(Play)路径坏了，不代表对讲(Talk)路径也坏。**

**建议实验（下一步）**：让 WVP 用**语音对讲**（平台主动 INVITE 设备，`s=Talk`/PCMA）而非**语音广播**（MESSAGE→设备 INVITE，`s=Play`/PS）向终端送音频：

| 流程 | 信令 | 终端 native 路径 | 预期 |
|------|------|------|------|
| 语音广播(现状) | 平台 MESSAGE(Broadcast) → 设备 INVITE(`s=Play`/PS) | 广播路径（**实测坏**） | 无声 |
| 语音对讲(待测) | 平台**直接 INVITE** 设备(`s=Talk`/PCMA) | 对讲下行 `fromJNItalkDownCallBackFun` → playPCM，并置 `talkState` | 可能出声 |

若对讲能出声，即绕开 native 广播 bug，是**可交付**的解法。待办：
1. 查 WVP 是否已有"语音对讲(平台主动 INVITE)"接口/流程，能否直接调用测试。
2. 若有，发起对讲并观察终端 `talkState` 是否置位、`onReceiveTalkAudioData`/`playPCM` 是否触发、是否出声。

### 8.4 本轮新增经验

12. **区分"框架能力缺失"与"实现 bug"要看 API 面**：终端广播只有回调、无启用方法 = 纯 native 自治，app 无从干预；由此判定根因必在 `.so` 内部，避免在 app/服务端徒劳找开关。
13. **同一 playPCM 出口的两条链路(对讲/广播)可互为备选**：当一条 native 链路疑似有 bug 时，优先尝试功能等价、实现更完整的另一条（本案对讲），往往比硬啃坏掉的链路更快落地。

---

## 九、2026-07-08 语音对讲(Talk)实测：与广播同因，服务端已排除

按第 8.3 节建议，将 WVP 语音对讲改为 **UDP 主动推流** 并实测。**结论：服务端全链路正确、RTP 实测到达设备，但设备与广播同样不出声——对讲假设被推翻。**

### 9.1 服务端代码改动（对讲改为 UDP 主动推流，已重建生效）

原对讲流程 offer 写死 **TCP 被动**（`TCP/RTP/AVP` + `a=setup:passive`），而该设备只应答 **UDP**（`RTP/AVP`），媒体协商冲突 → 媒体面根本没建起来。改动：

- `SIPCommander.talkStreamCmd`：SDP 按 `isTcp()` 分支，UDP 时 offer `m=audio <localPort> RTP/AVP 8` + `a=sendonly`。
- `PlayServiceImpl.talk`：`createSendRtpInfo(..., tcp=false, ...)`；不再开 TCP 被动监听；收到设备 200 OK 后解析其 `c=`/`m=audio` 地址端口，`setIp/setPort/setTcp(false)` 后 `mediaServerService.startSendRtp()` 主动 UDP 推流。

改后协商完全规范（同一会话 `ssrc=0200001310`）：

```
WVP offer (INVITE)              设备 200 OK (answer)
s=Talk                          s=Talk
c=IN IP4 192.168.0.40           c=IN IP4 192.168.0.62
m=audio 50503 RTP/AVP 8         m=audio 5084 RTP/AVP 8
a=sendonly                      a=recvonly
a=rtpmap:8 PCMA/8000            a=rtpmap:8 PCMA/8000
y=0200001310                    y=0200001310
```

WVP 日志 `[语音对讲] UDP主动推流, 目标: 192.168.0.62:5084`，ZLM `startSendRtp` 返回 **code=0**。

### 9.2 实测结果：RTP 到达设备 socket，但设备不读、talkState=0、无声

| 环节 | 实测 | 判定 |
|---|---|---|
| RTP 发送 | tcpdump：`172.18.0.2.50503 > 192.168.0.62.5084 UDP length 812`，持续 ~10pps | ✅ 在发 |
| 设备 socket 5084(`0x13DC`) | `/proc/net/udp`：绑定 `0.0.0.0:5084`，未 connect，`rx_queue=0x34100=213760` 钉死不排空 | ❌ 应用不读 |
| 设备对讲态 | logcat：`talkState:0` 全程为 0，无 `onReceiveTalkAudioData`/`playPCM` | ❌ 未激活 |
| 出声 | 无 | ❌ |

### 9.3 结论（与广播对照）

| | 语音广播(Broadcast) | 语音对讲(Talk) |
|---|---|---|
| 信令发起 | 平台 MESSAGE → 设备 INVITE(`s=Play`/PS) | 平台主动 INVITE(`s=Talk`/PCMA) |
| 服务端 RTP 到达设备 | ✅ | ✅ |
| 设备 socket | 绑定、`rx_queue` 钉死、不读 | 绑定、`rx_queue` 钉死、不读 |
| `talkState` | 0 | 0 |
| 出声 | 无 | 无 |

**两条下行路径症状完全一致。** 推翻 8.3"对讲路径完好"的假设，结论修正为：

> **【实测】** 该设备对**平台推来的下行音频**（无论广播 `s=Play`/PS 还是对讲 `s=Talk`/PCMA、无论 TCP 还是 UDP），SIP 层都回 200 OK 并绑定接收端口，RTP 也进了内核缓冲，**但应用层从不读取该 socket、对讲/播放态从不激活** → 无声。
>
> **【实测排除】** NAT、编码、ptime、传输协议(TCP/UDP)均非主因——包已进 socket 缓冲，是设备自己不读。
>
> **【推断】** 平台侧下行播放需设备端某种触发/发起或固件开关；平台单向 INVITE 只被 SIP 层接受，app 对讲态未启动。服务端无法修复。

### 9.4 后续（设备/厂商侧）

1. 联系终端厂商确认该型号平台侧下行对讲/喊话的**激活条件**（是否须设备端按键发起、固件开关、特定 SDP 字段）。
2. 若有厂商自带平台能成功喊话/对讲，抓其信令逐字对比。
3. 服务端 UDP 对讲改动正确且有价值（协商与发流已打通），**保留**。

### 9.5 复验：offer 改 `a=sendrecv`（双向）仍无效，方向假设推翻

依据终端协议文档《GB28181_Protocol_Analysis.md》业务3——**对讲是"双向音频(sendrecv)"，广播是"平台→终端单向"**，怀疑 9.1 的 `a=sendonly`（单向、语义等同广播）导致 native 未进对讲态。遂将 `SIPCommander.talkStreamCmd` 的 UDP 分支 offer 由 `a=sendonly` 改为 **`a=sendrecv`**，重建镜像并实测（2026-07-08，设备 IP `192.168.0.60`，`ssrc=0200007125`）：

```
WVP offer (INVITE)              设备 200 OK (answer)
s=Talk                          s=Talk
m=audio 50504 RTP/AVP 8         m=audio 5084 RTP/AVP 8
a=sendrecv                      a=recvonly          ← 设备仍强制 recvonly
y=0200007125                    y=0200007125
```

| 环节 | 实测 | 判定 |
|---|---|---|
| offer 方向 | `a=sendrecv`（已生效） | ✅ |
| 设备应答方向 | `a=recvonly`（与 sendonly 时**完全相同**） | 设备只收不发，无关 offer |
| RTP 发送 | tcpdump：`172.18.0.2.50504 > 192.168.0.60.5084 UDP length 812`，**仅下行、无上行** | ✅ 在发，设备无回流 |
| 设备对讲态 | logcat 全程 `liveState:0, talkState:0`，无 `onReceiveTalkAudioData` | ❌ 未激活 |
| 出声 | 无 | ❌ |

**结论修正**：
- **方向(sendonly/sendrecv)不是主因**——设备无论收到何种 offer 都回 `recvonly`，且 `talkState` 始终 0。
- 终端文档第 1375 行明确：**`talkState==2` 才会 `playPCM()` 播放下行对讲音频**；native 只在 talk 态才投递 `onReceiveTalkAudioData` 回调。实测 `talkState` 恒为 0 → 回调从不触发 → 必然无声。
- 至此**服务端 SIP/SDP/RTP 全链路已穷尽**（信令 200 OK、方向协商正确、RTP 实测到达设备选定端口），**问题完全落在闭源终端固件：平台主动 INVITE 无法使 native 置 `talkState=2`**，服务端不可控。

> **【最终判定】** 该型号终端对"平台推来的下行音频"（广播 `s=Play`/PS 与对讲 `s=Talk`/PCMA、TCP 与 UDP、sendonly 与 sendrecv 组合全测）均只在 SIP 层应答、绑定端口，但**应用层从不读 socket、`talkState`/播放态从不激活**。根因在固件 native 层，须终端厂商修复或提供平台侧下行的激活条件。

### 9.6 复验：完全无视频拉流的"纯 talk"干净环境，结论不变（排除拉流污染）

**背景**：此前所有对讲测试的"开始讲话"按钮都在 `devicePlayer.vue` 播放弹窗内，而该弹窗**默认拉视频**。结合第十一节实测"设备单会话、talk 会拆掉视频"，怀疑早期"对讲无声"可能是被并发视频拉流挤占了设备唯一会话所致。故做一次**零拉流**的干净复验。

**方法**（headless，不经前端、不拉视频）：利用 `PlayServiceImpl.onApplicationEvent(MediaArrivalEvent)` 的机制——**只要有音频流发布到 ZLM 的 `talk` app、流名 `{deviceId}_{channelId}`，WVP 就自动解析设备+通道并调 `talkCmd` 发起对设备 INVITE**（无需先调 API、无需登录 token）。直接从 ZLM 容器内用 ffmpeg 推一路 PCMA 正弦音：

```pwsh
# ZLM 容器内置 ffmpeg(/usr/bin/ffmpeg)；内部 RTMP 监听端口见 config.ini [rtmp] port=10001
docker exec docker-polaris-media-1 sh -c "ffmpeg -hide_banner -re -f lavfi -i sine=frequency=440:sample_rate=8000 -c:a pcm_alaw -ac 1 -f flv 'rtmp://127.0.0.1:10001/talk/35020000201311005331_35020000201311005331'"
```

**实测结果**（2026-07-08，设备 `192.168.0.60`）：

| 环节 | 实测 | 判定 |
|---|---|---|
| RTMP 推流鉴权 | `OnPublish` 通过（`params=''` 无 sign 也放行），`enable_audio=true` | ✅ 流已进 ZLM |
| WVP 触发 | 自动 `talkCmd`，offer `s=Talk a=sendrecv` → 设备 `a=recvonly` | ✅ 管线全通 |
| RTP 发送 | tcpdump：`172.18.0.2.50503 > 192.168.0.60.5080 UDP length 812`，~8pps，701 包实证 | ✅ 稳定送达 |
| 设备 socket 5080(`0x13D8`) | `/proc/net/udp`：`rx_queue=0x34100=213760` **5 次采样全程钉死不排空** | ❌ 设备仍不读 |
| 视频拉流 | **全程无**（本次刻意隔离该变量） | — |

**结论**：
- **无视频拉流时，设备依旧不读取对讲 socket**（`rx_queue` 钉死值与有拉流时完全一致 `0x34100`）。**"拉流污染"假设被排除**，第 9 节根因结论成立且更稳固。
- **两个独立问题彻底分离**：①"talk 打断视频"= 设备单会话（见第十一节，纯 talk 页面可规避）；②"talk 无声"= 设备不激活接收管线（与拉流无关，纯 talk 页面**无法**解决）。

> **【方法论收获】** 绕开前端直接向 ZLM 的 `talk/{deviceId}_{channelId}` 推流即可 headless 触发整条对讲链路（WVP `MediaArrivalEvent` 自动发起 INVITE+发流），是验证"对讲下行"最快的手段，且能干净隔离前端/拉流等变量。

---

## 十、抓包与取证方法（可复用）

本案定位全靠"**服务端在发**"与"**设备不读**"两个正交证据。以下命令在 Windows + Docker Desktop 环境实测可用（PowerShell）。

### 10.1 服务端：确认 ZLM 是否真的把 RTP 发到设备

在 ZLM 容器内 tcpdump（容器无 tcpdump 时自动安装）：

```pwsh
# 抓 ZLM 发往 LAN 网段(排除小包)的 UDP，确认 RTP 目标 IP/端口/包长/速率
docker exec docker-polaris-media-1 sh -c "which tcpdump || (apt-get update -y >/dev/null 2>&1 && apt-get install -y tcpdump >/dev/null 2>&1); tcpdump -n -tt -i any 'udp and net 192.168.0.0/24 and greater 60' 2>&1"
```

判读：
- 出现 `172.18.0.2.<srcPort> > 192.168.0.62.<dstPort>: UDP, length N` = ZLM 在发，目标正确。
- `length 812`≈100ms/包(G711A)，`length ~172`≈20ms/包——可判断 ptime。
- 只看 ZLM `startSendRtp` 返回 code=0 **不够**，那只代表"发送方在发"，必须 tcpdump 实证到达路径。
- 停止：`docker exec docker-polaris-media-1 sh -c "pkill tcpdump"`。

### 10.2 服务端：抓 WVP 的 SDP 协商与对讲日志

```pwsh
# 实时跟踪对讲/信令关键行
docker logs -f --tail 0 docker-polaris-wvp-1 2>&1 | Select-String -Pattern "对讲|Talk|INVITE|200 OK|m=audio|UDP主动推流|startSendRtp|SDP"

# 事后回捞最近 6 分钟的完整 SDP(offer+answer)
docker logs --since 6m docker-polaris-wvp-1 2>&1 | Select-String -Pattern "v=0|o=|s=Talk|s=Play|c=IN|m=audio|a=rtpmap|a=sendonly|a=recvonly|y=|f=v" | Select-Object -Last 60
```

> 注：WVP 中文日志经 `docker logs` 可能乱码，但 SDP/参数是 ASCII，不影响判读。

### 10.3 设备端：判定"设备收到不读"还是"根本没收到"（最关键判据）

前提：设备开启 USB 调试，`adb` 可用（本机 adb：`C:\Users\jiyua\Downloads\ADB操作安装app_4.26\ADB操作安装app_4.26\adb.exe`）。

```pwsh
$adb="C:\Users\jiyua\Downloads\ADB操作安装app_4.26\ADB操作安装app_4.26\adb.exe"
& $adb devices                       # 确认设备在线(USB)

# 关键：读 /proc/net/udp 看接收端口的 rx_queue。端口需转 16 进制大写：5084 -> 13DC
# 发起对讲/喊话并持续讲话时，连续采样 3 次
1..3 | ForEach-Object { & $adb shell "cat /proc/net/udp | grep 13DC"; Start-Sleep -Milliseconds 800 }
```

判读 `/proc/net/udp` 一行的字段（空格分隔）：
```
sl  local_address rem_address  st tx_queue:rx_queue ...
988: 00000000:13DC 00000000:0000 07 00000000:00034100 ...
     |            | |          |     |        |
     0.0.0.0:5084  未connect        tx=0     rx=0x34100=213760 字节
```
- `rem_address=00000000:0000` = 未 `connect()`，内核不按源地址过滤（**故 NAT 改写源端口不会导致丢包**）。
- **`rx_queue` 持续钉在高位(缓冲上限)不下降 = 应用层从不 `recv()`**（收到不读）。
- `rx_queue` 恒为 0 且无包 = 根本没收到（查 NAT/路由/目标端口）。
- `rx_queue` 边涨边落波动 = 应用在正常读取。

端口转十六进制：`printf '%04X' 5084` → `13DC`（或用计算器）。

### 10.4 设备端：观察国标状态机与音频回调

```pwsh
$adb="C:\Users\jiyua\Downloads\ADB操作安装app_4.26\ADB操作安装app_4.26\adb.exe"
& $adb logcat -c                     # 清空
# 发起对讲后抓取，关注 talkState/接收回调
& $adb logcat -d 2>&1 | Select-String -Pattern "onStateUpdate|talkState|onReceiveTalk|onReceiveBroadcast|playPCM|sip_call|cm_add|sua_start"
```

判读：
- `talkState:0` 全程不变 = 对讲态未激活。
- 无 `onReceiveTalkAudioData`/`onReceiveBroadcastAudioData`/`playPCM` = native 一个音频包都没消费。
- 该设备 App 为 `com.dcw.vochat`，国标状态 tag 为 `Gb28181Local`；camera/display 日志(`BufferQueueProducer`/`MtkCam`)是噪声，需过滤。

### 10.5 取证套路总结

1. **两个正交证据缺一不可**：tcpdump 证"服务端发到了"，`/proc/net/udp` 的 `rx_queue` 证"设备读没读"。二者结合才能干净地把根因从服务端切到设备。
2. **`rx_queue` 是终极判据**，比 logcat 更底层、不可抵赖：钉死=收到不读，为 0=没收到，波动=正常。
3. **改一处→重建→只看一个信号**，避免一次改多处分不清谁起作用。

---

## 十一、2026-07-08 实测：一点"开始讲话"视频拉流即断——设备单会话固件限制

**现象**：前端把视频拉流与语音喊话/对讲放在同一播放页；先拉流出图正常，**一点"开始讲话"，视频立即中断**。疑问：终端是否不支持"边拉流边对讲"？

### 11.1 三层排查（前端 / WVP / 设备）

用 tcpdump（ZLM 容器）+ logcat + 代码审查三方交叉定位：

| 层 | 结论 | 依据 |
|---|---|---|
| **前端** | **不停视频** | `devicePlayer.vue` 的 `broadcastStatusClick`→`play/broadcastStart` 只发起 WebRTC 推流拿 RTC 地址，全程不触碰视频播放器实例 |
| **WVP 后端** | **不拆点播** | `PlayServiceImpl.audioBroadcast`/`audioBroadcastCmd` 仅创建/管理广播·对讲会话；无 `stopPlay`、不对 PLAY 的 INVITE 发 BYE；运行日志无"停止点播" |
| **设备** | **自己停推视频** | tcpdump 实测：点讲话瞬间设备视频 RTP 入流消失（见 11.2） |

### 11.2 tcpdump 决定性证据（设备主动停推视频）

因 Docker Desktop NAT，设备上行 RTP 源地址被网关改写为 `172.18.0.1`，需**不限 host 抓大包**才能看到设备视频入流：

```pwsh
docker exec docker-polaris-media-1 sh -c "tcpdump -n -tt -i any 'greater 300 and (tcp or udp) and not port 3306 and not port 6379 and not port 18978' 2>&1"
```

- **点讲话前（视频正常）**：`In 172.18.0.1.34958 > 172.18.0.2.31022 UDP length 974` 持续 —— 设备视频 RTP 经 NAT 网关到达 ZLM 收流端口 `31022`；ZLM 再从 `18000` 分发给 WebRTC 播放器。
- **点讲话瞬间**：`>31022` 视频入流**完全消失**，仅剩对讲出流 `172.18.0.2.50502 > 192.168.0.60.5080 length 812`。**设备停止了视频推流。**

### 11.3 关键教训：`liveState`/`talkState` 不可作为 GB28181 点播/对讲的判据

实测期间设备一直在推视频（tcpdump 974B 包实证），但 logcat 的 `onStateUpdate` **全程 `liveState:0, talkState:0`**。说明该固件这两个状态变量**根本不跟踪 GB28181 的点播/对讲**（疑似只反映厂商自有平台 live/对讲）。**本案早期据 `liveState/talkState` 做的"未激活"判断需作废，改以 tcpdump（在发）+ `/proc/net/udp rx_queue`（读没读）为准。**

### 11.4 定论与对"可并行"说法的修正

> **【实测定论】** 该终端固件的 native SIP/媒体栈**只维持一路媒体会话**。收到第二个 INVITE（音频喊话/对讲）时，它会拆掉正在进行的视频推流去服务音频 —— **是设备单会话限制，不是前端或 WVP 的 bug**。

**修正**：此前"终端支持边拉流边对讲"的判断仅基于终端 Java 上行采集代码（`Gb28181Local.pushAudioData` 中对讲上行与视频上行是两个并列 `if`、互不 `return`）。那只反映 **app 层音频采集设计不互斥**，**不能推导** native 会话栈可并发。**实测行为推翻了代码层推断。**

### 11.5 对策（产品/前端侧）

1. **接受限制**：视频点播与喊话/对讲不可同时；设备会强制二选一。
2. **前端 UX 兜底**：点"开始讲话"时主动暂停/隐藏视频，讲话结束自动恢复拉流（反正设备也会拆，不如让交互平滑、避免"画面卡死"观感）。
3. **问厂商**：确认该型号固件是否支持并发媒体会话或有相关开关。

---

## 十二、2026-07-09 广播出声实测：完整可复现方法（✅ 人耳确认）

> 目的：一次性验证"平台→终端喊话"端到端出声。本方法**当场让设备扬声器播出清晰 600Hz 测试音**，用户现场确认。

### 12.1 环境与关键值

| 项 | 值 |
|---|---|
| WVP API | `http://127.0.0.1:18978` |
| 登录 | `admin` / MD5 `21232f297a57a5a743894a0e4a801fc3` |
| 设备ID/通道ID | `35020000201311005331` / `35020000201311005331` |
| ZLM 容器名 | `docker-polaris-media-1`（内置 `/usr/bin/ffmpeg`） |
| ZLM RTMP 收流端口 | `10001`（`.env` `MediaRtmp`，容器内外 1:1） |
| adb 路径 | `D:\ADB\adb.exe`（主机未装 ffmpeg，故推流走容器） |
| 广播流名 | `broadcast/{deviceId}_{channelId}` |

### 12.2 步骤（PowerShell）

**① 记录设备日志基线行号**（用于事后只看新增行）：
```pwsh
D:\ADB\adb.exe shell "wc -l < /sdcard/log.txt"     # 记下基线，如 72566
```

**② 登录取 token → 容器内推 600Hz PCMA 测试音 → 触发广播**（一条链完成，token 不落盘）：
```pwsh
$t = (curl.exe -s "http://127.0.0.1:18978/api/user/login?username=admin&password=21232f297a57a5a743894a0e4a801fc3" | ConvertFrom-Json).data.accessToken
# 先在 ZLM 容器内起 ffmpeg 推流(-d 后台)，让 broadcast 流就绪
docker exec -d docker-polaris-media-1 ffmpeg -re -f lavfi -i "sine=frequency=600:sample_rate=8000:duration=35" -c:a pcm_alaw -ac 1 -f flv "rtmp://127.0.0.1:10001/broadcast/35020000201311005331_35020000201311005331"
Start-Sleep -Seconds 2
# 触发广播(设备主动 INVITE s=Play)
curl.exe -s -H "access-token: $t" "http://127.0.0.1:18978/api/play/broadcast/35020000201311005331/35020000201311005331?timeout=30&broadcastMode=true"
```
预期返回 `{"code":0,"msg":"成功","data":{...,"codec":"G.711","app":"broadcast",...}}`。

**③ 现场听声 + 抓日志验证数据到达**（基线之后的新行）：
```pwsh
D:\ADB\adb.exe shell "tail -n +72567 /sdcard/log.txt | grep -anE 'Play encoder|Broadcast_StreamDown|BroadcastCallBack' | tail -30"
```
预期持续刷屏：
```
sip_audio_cb Play encoder:PS
Broadcast_StreamDown Add data back:len=[640] ,ts=[0] ,seq=[0]
Broadcast_StreamDown running CallBack Fun
funCallbackJavaFromJNIBroadcastCallBack======broadcastCallBack>>>len=640 , ts=0
```
**同时设备扬声器应播出清晰 600Hz 持续音。**

**④ 收尾**（停广播 + 杀推流）：
```pwsh
$t = (curl.exe -s "http://127.0.0.1:18978/api/user/login?username=admin&password=21232f297a57a5a743894a0e4a801fc3" | ConvertFrom-Json).data.accessToken
curl.exe -s -H "access-token: $t" "http://127.0.0.1:18978/api/play/broadcast/stop/35020000201311005331/35020000201311005331"
docker exec docker-polaris-media-1 pkill -f ffmpeg
```

### 12.3 结果判读

| 现象 | 结论 |
|---|---|
| 听到清晰 600Hz + 日志刷 `BroadcastCallBack` | ✅ 广播链路端到端打通（**本次实测结果**） |
| 日志刷回调但没声音 | 数据到 `playPCM` 但设备音频输出层有问题（音量/焦点/`AudioTrack`/PCM 格式） |
| 日志无 `Play encoder`/`Broadcast_StreamDown` | RTP 没到设备，查 NAT/目标地址端口（见第七、十节） |

### 12.4 传输层实测：广播走 **TCP**（设备 active / 服务端 passive）

> 结论：**本次成功出声的喊话，媒体流是 TCP，不是 UDP**，且**由设备在 SDP 里自选**。

设备 native 日志（13:06:23 那次广播）：

**设备发出的 INVITE（作为主叫 UAC）：**
```
sua_build_sdp_msg [audio] device TCP/RTP local port:49002
s=Play
m=audio 49002 TCP/RTP/AVP 96 8      ← TCP/RTP/AVP（非 RTP/AVP）
a=setup:active                        ← 设备主动连出
```
**WVP 回的 200 OK：**
```
s=Play
m=audio 50506 TCP/RTP/AVP 96          ← 同样 TCP
a=setup:passive                        ← 服务端被动监听
```

要点：
- **传输层 = TCP**（`TCP/RTP/AVP`）；设备 `setup:active` 主动连出，ZLM `setup:passive` 在 `50506` 被动等待。
- 端口 `50506` 落在 `docker-compose.yml` 第 56 行 `50502-50506:50502-50506/tcp` 映射范围内（**只映射了 TCP**），故连通、出声。
- **是设备自选 TCP**：`s=Play` 模式下该固件默认 `TCP/RTP/AVP` + `a=setup:active`，非服务端指定。与此前给"对讲(`s=Talk`)"改的 UDP 主动推流是两条不同的路径。
- **部署提醒**：喊话依赖 `50502-50506/tcp` 端口对外可达（被动监听）。若换端口范围或只放通 UDP，会导致设备 TCP 连不上→无声。

### 12.5 UDP vs TCP 实测对比：配置需重启生效；UDP 到内核但 app 不读（决定性）

> 背景：用户在终端修改"广播传输配置"并**重启设备**后复测。本节含一处**结论纠正**。

**① 配置生效需重启**：重启后设备广播 SDP 从 `TCP/RTP/AVP`+`a=setup:active` 变为 **UDP**：
```
sua_build_sdp_msg [audio] device UDP/RTP NEW port:49002
m=audio 49002 RTP/AVP 96 8            ← 变 UDP（RTP/AVP），无 a=setup
```
WVP 侧正确解析并推流（Docker NAT 地址修正生效）：
```
InviteRequestProcessor: SDP o=(172.18.0.1) 与 Via sent-by(192.168.0.62) 不一致，使用 Via sent-by 作为 RTP 目标
MediaServerService: [开始推流] 目标=192.168.0.62:49002
ZLM: is_udp=1, dst_url=192.168.0.62, dst_port=49002, use_ps=1, only_audio=1 → code=0
```

**② ⚠️ 结论纠正**：初测时仅凭"app 日志无 `Broadcast_StreamDown`/回调"就判"UDP 包到不了设备"，**此判断错误**。用 `/proc/net/udp`（端口 49002 = `BF6A`）复核，两次采样：
```
sl  local_address  rem_address    st tx_queue:rx_queue        ... drops
874: 00000000:BF6A 00000000:0000  07 00000000:00034100        ... 197   ← 采样1
874: 00000000:BF6A 00000000:0000  07 00000000:00034100        ... 214   ← 采样2
```
- `rx_queue = 0x34100 = 213760 字节`，**钉死在缓冲上限**；`drops` **197→214 持续增长**。
- 铁证：**UDP 包确实到达设备内核**，只是 **app 层从不 `recv()`**，缓冲塞满后内核丢包。
- **教训（再次印证 §10.5）**：判"包到没到设备"必须看 `/proc/net/udp` 的 `rx_queue`/`drops`，**app 日志的回调缺失在"到内核但 app 不读"时同样为空**，不能作为"未到达"的判据。

**③ 真正的差异是 TCP vs UDP，不是 broadcast vs talk**：

| 场景 | UDP/TCP | 包到设备内核 | app 是否 `recv()` | 结果 |
|---|---|---|---|---|
| 广播（重启前） | TCP，设备 `setup:active` 拨出 | —（TCP 流） | ✅ 读 → `Broadcast_StreamDown`→`playPCM` | **出声** |
| 广播（重启后） | UDP，ZLM 推 | ✅（`rx_queue` 钉死、`drops` 增长） | ❌ 不读 | 无声 |
| 对讲（2026-07-08，见九节） | UDP，ZLM 推 | ✅（`rx_queue` 钉死） | ❌ 不读 | 无声 |

**两个 UDP 场景（对讲、广播）行为完全一致，不矛盾**：包都到设备内核，但该固件 **app 层的 UDP 音频接收消费端根本没运行**；唯有 **TCP 路径（设备 `setup:active` 主动拨出）** 的接收线程会真正读并播放。

**④ 定论修正**：此前把 UDP 无声归因于"Docker NAT 打不通入向 UDP"**也是错的**——`/proc/net/udp` 证明包已到设备内核。真因是**终端固件的 UDP 收流在 app 层未被消费**。

**⑤ 对策**：**喊话保持 TCP**（设备 `s=Play`+`a=setup:active` 主动拨出，实测出声）；不要把终端广播配置改成 UDP（该固件 UDP 收流 app 不消费 → 无声）。

### 12.7 终端"传输模式"配置对广播的实际影响（含 PassiveTCP 不生效）

> 结论：该固件广播 `s=Play` 只有 **ActiveTCP**（默认、可出声）与 **UDP**（可切换但无声）两种实际生效状态；**PassiveTCP 配置对广播完全不生效**（疑似设备侧配置代码 bug）。

**当天五次实测总表**（设备 ID `35020000201311005331`，均 `broadcastMode=true`、推 600Hz PCMA）：

| # | 时间 | 终端配置 | 是否重启 | 设备 SDP（设备自身 INVITE） | 传输 | 音频回调 | 结果 |
|---|------|---------|---------|------------------------------|------|---------|------|
| 1 | 13:06 | ActiveTCP | - | `m=audio 49002 TCP/RTP/AVP 96 8` / `a=setup:active` | TCP | 刷屏 | ✅ 出声（人耳确认） |
| 2 | 13:44 | ActiveTCP | - | 同上，逐字一致 | TCP | 刷屏 | ✅ 出声 |
| 3 | 13:49 | UDP | ✅ | `m=audio 49002 RTP/AVP 96 8`（无 `a=setup`） | UDP | **0** | ❌ 无声（包到内核 app 不读，见 12.5） |
| 4 | 14:16 | PassiveTCP | ❌ | `m=audio 49002 TCP/RTP/AVP 96 8` / `a=setup:active` | TCP | 432 | ✅ 出声 |
| 5 | 14:20 | PassiveTCP | ✅ | `m=audio 49002 TCP/RTP/AVP 96 8` / `a=setup:active` | TCP | 432 | ✅ 出声 |

**判读：**

- **配置是否生效，只看设备自身 INVITE 的 SDP 变没变**：
  - **UDP 开关有效** —— 第 3 次设备 SDP 明显变了（`RTP/AVP`、无 `a=setup`、`device UDP/RTP NEW port`）。
  - **PassiveTCP 开关无效** —— 第 4、5 次设备 SDP 与 ActiveTCP（第 1、2 次）**逐字完全相同**，始终 `a=setup:active` + `device TCP/RTP local port:49002`。**即使确认修改并重启（第 5 次）仍不变。**
- **若 PassiveTCP 真生效**，设备应发 **`a=setup:passive`**（设备被动、等 ZLM 反向 TCP 连它的 49002），但实测设备恒发 `active`。
- **易混淆点**：200OK 里的 `a=setup:passive`（第 830/834 行等）是 **WVP 服务端**的角色（服务端一贯被动等设备连），**不是设备**的 setup 角色；判定设备模式必须看设备自己 INVITE 的 `a=setup`。

**定论：**

1. **该终端固件的 PassiveTCP 配置项对广播 `s=Play` 不生效**（设备侧配置代码问题：开关未作用到广播 SDP 的 `setup` 属性；可能只对对讲/点播或压根未实现）。
2. **广播实际可用路径唯有 ActiveTCP**（设备 `a=setup:active` 主动拨出 → 服务端被动收 → app 读 → `playPCM` 出声）。
3. **生产建议：喊话固定用默认/ActiveTCP，不要配 UDP（无声）或 PassiveTCP（不生效）。**

### 12.8 常见坑

1. **主机没 ffmpeg** → 走 `docker exec ... ffmpeg`（ZLM 官方镜像自带），推 `rtmp://127.0.0.1:10001`（容器内视角）。
2. **`adb` 不在 PATH** → 用全路径 `D:\ADB\adb.exe`。
3. **时间不同步导致"没听到"** → 必须**人在设备旁 + 推流同时进行**；本案早期"没声音"实为 12:35 推流时人不在场，13:06 同步复测即听到。
4. **必须 `broadcastMode=true`** → 走"设备主动 INVITE `s=Play`"路径；这是本固件唯一可用的下行音频通路（对讲 `s=Talk` 下行固件不支持）。

---

## 十三、2026-07-09 TCP 对讲实验：改造 + 测试方法 + 两轮实测（决定性证实 §11）

> 动机：广播 `s=Play` 在 TCP 下出声、UDP 下无声（第十二节）。据此提出假设——**对讲 `s=Talk` 改用 TCP 是否也能出声？** 本节改代码 + 重建镜像做实证。
> 结论：**否。对讲下行不出声与传输层无关，设备固件对 `s=Talk` 恒 `talk_rx:0`（只上行）。两轮实测 100% 一致。**

### 13.1 代码改造（把对讲从 UDP 主动推流改为 TCP 被动，与已跑通的 TCP 广播同构）

| 文件 | 改动 |
|---|---|
| `PlayServiceImpl.talk()` | `createSendRtpInfo(..., tcp=true)`；`setTcpActive(true)`（设备 active / 服务端 passive）；okEvent 里把"解析设备 SDP→UDP 主动推流"替换为 `mediaServerService.startSendRtpPassive(...)`（ZLM 监听本地端口等设备连） |
| `SIPCommander.talkStreamCmd()` | TCP 分支的 `m=audio` 端口由值为 0 的 `getPort()` 修正为 `getLocalPort()`（原 TCP 分支从未真正可用） |

改后 WVP 的对讲 INVITE：`m=audio <localPort> TCP/RTP/AVP 8` + `a=setup:passive`（与广播 200OK 同构）。

### 13.2 如何模拟触发/测试"对讲"（无需前端 WebRTC）

对讲不像广播有 `/api/play/broadcast` 入口，而是**由"向 `talk` app 推流"触发 hook 链**发起：

> 触发链：向 ZLM 推流 `talk/{deviceId}_{channelId}` → ZLM `on_stream_changed(rtsp,regist)` → WVP `MediaArrivalEvent`（**仅 rtsp schema 发布**，见 `ZLMHttpHookListener:146`）→ `PlayServiceImpl.onApplicationEvent` → `talkCmd` → 向设备发 `s=Talk` INVITE。

步骤：

1. 记录设备日志基线：`D:\ADB\adb.exe shell "wc -l < /sdcard/log.txt"`
2. **向 `talk` app 推 PCMA 流**（容器内 ffmpeg），即可触发对讲 INVITE：
   ```pwsh
   docker exec -d docker-polaris-media-1 sh -c "ffmpeg -re -f lavfi -i 'sine=frequency=600:sample_rate=8000:duration=35' -c:a pcm_alaw -ac 1 -f flv 'rtmp://127.0.0.1:10001/talk/35020000201311005331_35020000201311005331' > /tmp/ff.log 2>&1"
   ```
   - talk app 推流**默认放行**（`MediaServiceImpl.authenticatePublish` 对 talk/broadcast 返回 `enable_audio=true`，无需预注册鉴权）。
   - 校验流已注册：`curl "http://127.0.0.1:6080/index/api/getMediaList?secret=<zlm_secret>&app=talk"`。
3. 抓设备日志判定（基线之后）：
   ```pwsh
   D:\ADB\adb.exe shell "tail -n +<baseline+1> /sdcard/log.txt | grep -anE 'calltype|s=Talk|m=audio|a=setup|talk_rx|talk_tx|talkUP_startStream'"
   D:\ADB\adb.exe shell "tail -n +<baseline+1> /sdcard/log.txt | grep -acE 'talk_StreamDown|talkDownCallBack|onReceiveTalk'"   # 下行回调计数
   ```
4. **排坑**：
   - WVP 日志为 **GBK 乱码**（"语音对讲"→乱码），用中文关键字 grep 会漏；判定以**设备日志**为准，或 grep WVP 的 `messageSize=...INVITE...s=Talk`。
   - `on_stream_changed` WVP 只在 **schema=rtsp** 时发 `MediaArrivalEvent`；ZLM 每 schema 各发一次 hook，勿被 rtmp/ts/fmp4 干扰。

### 13.3 两轮实测结果（完全一致）

| 项 | 第一轮 14:49 | 第二轮 14:56 |
|---|---|---|
| WVP INVITE | `m=audio 50503 TCP/RTP/AVP 8` `a=setup:passive` | `m=audio 50504 TCP/RTP/AVP 8` `a=setup:passive` |
| 设备识别 | `sip_call_in calltype = Talk` | 同 |
| **设备应答** | `m=audio 5080 RTP/AVP 8`（**无视 TCP，回 UDP**） | 同 |
| **会话状态** | `sua_state_set type:Talk talk_rx:0 talk_tx:0` | 同 |
| 只启动 | `talkUP_startStream`（上行） | 同 |
| **下行回调** | **0** | **0** |

差异仅 WVP 监听端口 `50503→50504`（端口池轮换），对结果无影响。

### 13.4 定论

1. **WVP 侧改造有效**：确实发出 TCP 被动的 `s=Talk` INVITE。
2. **设备固件否决对讲下行**：无视 TCP 提议、固定回 UDP，且 `sip_call_in` 将 `Talk` 分类后 **`talk_rx:0`**（只跑上行 `talkUP_startStream`），下行回调恒 0 → **无声**。
3. **决定"喊话能否出声"的是会话类型而非传输层**：`s=Play` 启用下行（TCP 可出声）；`s=Talk` 在状态机层关死下行，**换 TCP/UDP 均无效**。这是对 `zx/GB28181_Protocol_Analysis.md` §11（`talk_rx=bit11` 恒为 0）的**动态实测级验证**。
4. **工程结论**：平台→终端下行音频只能用**语音广播(`s=Play`)+ TCP**；对讲(`s=Talk`)下行不可用，勿再尝试用对讲让设备出声。TCP 对讲改动为实验性质，可回退。

---

## 参考资料

- 腾讯云《Android 平台 GB28181 设备接入端如何支持跨网段语音对讲？》— s=Talk(裸PCMA) vs s=Play(PS) 两模式，及"Play 模式须回 PS"。
- CSDN《GB28181 PS 打包纯音频》— 语音广播/对讲纯音频 PS 打包的必要性与结构。
- ZLMediaKit issue #1891 — webrtc 推 PCMA、startSendRtp 用 `use_ps=1&pt=96&only_audio=1` 推国标设备，ZLM 走 `PSEncoder`（证明 ZLM 支持纯音频 PS）。
- ZLMediaKit issue #2201 / #2217 — GB28181 语音广播/对讲流程与"设备无声"同类咨询。
