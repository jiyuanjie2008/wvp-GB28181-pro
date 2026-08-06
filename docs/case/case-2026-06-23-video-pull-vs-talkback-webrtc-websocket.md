# 案例：拉视频、喊话、对讲三种业务的链路差异 —— 为什么当前配置能拉流却不能对讲

> 创建时间：2026-06-23（修订：补全 GB28181 广播/对讲方向性与 WVP 代码实证；新增 IP 过期/localhost 发现与第八节"HTTP 下跑通对讲"配置步骤；2026-06-29 修正 ZLM CORS 误判——`allow_cross_domains=1` 已开，撤销 nginx 代理层；修正 Stream_IP 误判——Stream_IP 保持局域网 IP，无需改为 localhost；新增第九章生产环境 HTTPS 部署方案；明确 config.ini 改后须重启 ZLM 容器）
> 影响范围：Docker 部署下的 GB28181 实时点播（拉视频）、语音广播（喊话）、语音对讲（对讲）
> 涉及组件：浏览器、polaris-nginx、polaris-wvp、polaris-media (ZLM)、GB28181 终端（如 `35020000201311005331`）
> 关联文档：[`case-2026-05-21-nginx-zlm-port-mapping.md`](./case-2026-05-21-nginx-zlm-port-mapping.md)

---

## 一、问题现象

同一套 Docker 部署下：

- **从前端拉取终端视频流（实时点播）**：经 `http://localhost:8090` 访问正常，画面能播（`localhost:6080` 始终指向本机 ZLM，不受 DHCP IP 过期影响——详见 7.5）。
- **与终端语音喊话 / 对讲**：失败——点击"开始对讲"后卡在"等待接通中…"，终端始终不发声。

最初的怀疑是"`80→6080` 的端口映射造成了失败"。**经排查，这个怀疑不成立**：

- 实测同一对讲接口，经 nginx 后 WebRTC 信令地址已被 `sub_filter` 正确改写为 `:6080`（与视频一致），ZLM 的 `:6080` 也可达。
- 真正的根因是：**"拉视频""喊话""对讲"在浏览器侧走的媒体通道和对浏览器权限的要求不同，而当前部署只为"拉视频"那条通道做了配置。**

---

## 二、核心结论（先看这段）

GB28181 把平台与终端之间的语音业务分成两种，方向性不同；再加上实时点播，共三种业务：

| 维度           | 拉视频（实时点播）                 | 喊话 / 语音广播（Broadcast） | 对讲 / 语音对讲（Talk）           |
| ------------ | ------------------------- | -------------------- | ------------------------- |
| GB28181 媒体方向 | 终端 → 平台（**单向**）           | 平台 → 终端（**单向**）      | 平台 ↔ 终端（**双向**）           |
| 浏览器角色        | **消费者**（只读流）              | **生产者**（只推麦克风）       | **生产者 + 消费者**（推麦克风 + 听对方） |
| 浏览器侧传输       | HTTP / WebSocket（FLV/HLS） | WebRTC（推音频）          | WebRTC（推音频）+ 播放对方音频       |
| 浏览器是否需要权限    | 不需要                       | **要麦克风 → 必须 HTTPS**  | **要麦克风 → 必须 HTTPS**       |
| 当前部署是否满足     | ✅ 满足                      | ❌ 不满足                | ❌ 不满足                     |

一句话：**拉视频能成，是因为它只让浏览器用 HTTP 拉流（已配）；喊话 / 对讲不成，是因为它们都要浏览器用 WebRTC 推麦克风音频，而 WebRTC 额外需要的 HTTPS / UDP 媒体口 / externIP 都没配。** 对讲比喊话还多一个"浏览器要听对方"的消费方向，但只要麦克风推流这一步通不了，两条都起不来。

> 注：这里的"必须 HTTPS"针对**非环回**访问（如 `http://192.168.x.x:8090`）；若在平台本机用 `http://localhost` 访问，localhost 属浏览器安全上下文，麦克风同样放行、**无需 HTTPS**（详见 7.5、第八节"在 HTTP 下跑通"）。

---

## 三、三种业务的流程对比

### 3.1 拉视频（终端 → 浏览器）—— 浏览器只消费

```
浏览器 --GET /api/play/start/{dev}/{ch}--> WVP
WVP    --SIP INVITE(a=recvonly，带 ZLM 收流 IP:端口:SSRC)--> 终端
终端    --RTP 推流(PS/H264，通常还带 PCMA 音频)-----------> ZLM   (终端推，ZLM 收)
                                                      ZLM   注册 rtp 流，回调 on_stream_changed
浏览器 <--ws-flv / hls(HTTP/WS 拉流)--------------------- ZLM   (浏览器拉，普通 HTTP 请求)
        经 nginx 80→6080 + /rtp/ 反代 + sub_filter
```

要点：浏览器只是**拉一条 FLV/HLS 流**，走 HTTP/WebSocket，没有任何高权限 API，也不需要 WebRTC。WVP 点播 INVITE 的 SDP 是 `a=recvonly`（平台只收）。

### 3.2 喊话 / 语音广播（平台 → 终端，单向）—— 浏览器只生产

```
浏览器 --GET /api/play/broadcast/{dev}/{ch}?broadcastMode=true--> WVP   (app=broadcast)
浏览器 --getUserMedia(麦克风)------------------------> [需要 HTTPS！]   ← 断点①
浏览器 --WebRTC 推音频(SRTP/UDP)---------------------> ZLM   (浏览器推，ZLM 收)
        需：信令 /index/api/webrtc + UDP 8000 + externIP/ICE        ← 断点②③
        ZLM   注册 broadcast 流 → WVP 触发 audioBroadcastCmd
WVP    --SIP MESSAGE Broadcast-----------------------> 终端
终端    --SIP INVITE(回拉音频)-----------------------> WVP/ZLM
ZLM    --RTP 音频(只有平台→终端这一向)---------------> 终端   (终端放喇叭)
```

要点：`broadcastMode=true` 走 `audioBroadcastCmd`。媒体**只有平台→终端一个方向**，浏览器只负责把麦克风推上去（生产者），不接收终端音频。

### 3.3 对讲 / 语音对讲（平台 ↔ 终端，双向）—— 浏览器既生产又消费

```
浏览器 --GET /api/play/broadcast/{dev}/{ch}?broadcastMode=false-> WVP   (app=talk)
浏览器 --getUserMedia(麦克风)------------------------> [需要 HTTPS！]   ← 断点①
浏览器 --WebRTC 推音频(SRTP/UDP)---------------------> ZLM   (浏览器推，ZLM 收)   【生产者】
        ZLM   注册 talk 流 → WVP 触发 talkCmd → talk()
WVP    --SIP INVITE(a=sendrecv 双向，PCMA)-----------> 终端
终端    =============================================> 双向 RTP/PCMA
        ← ZLM 把浏览器音频转发给终端(平台→终端)            终端听到平台
        → 终端把自身麦克风音频推回 ZLM(终端→平台)           到达 receiveStream={stream}_talk
浏览器 <--播放对方音频(消费 talk/_talk 回传流)--------- ZLM   【消费者】
```

要点：`broadcastMode=false` 走 `talkCmd → talk()`。INVITE 的 SDP 是 **`a=sendrecv`（双向）**，平台同时建好"发流（浏览器麦克风→终端，`startSendRtpPassive`）"和"收流（终端麦克风→平台，`receiveStream = stream + "_talk"`）"两路。**浏览器既是生产者（推自己麦克风）又是消费者（播放对方回传音频）**——这才是真正的"对讲"。

### 3.4 三者差异要点

|                 | 拉视频                       | 喊话(Broadcast)    | 对讲(Talk)              |
| --------------- | ------------------------- | ---------------- | --------------------- |
| 媒体方向            | 终端→平台                     | 平台→终端            | 平台↔终端                 |
| INVITE 的 SDP 方向 | `a=recvonly`              | （设备回 INVITE，平台发） | `a=sendrecv`          |
| 浏览器读还是写         | 读                         | 写                | 既读又写                  |
| 浏览器↔ZLM 协议      | HTTP/WS                   | WebRTC           | WebRTC                |
| 浏览器要不要权限        | 不要                        | 要麦克风→HTTPS       | 要麦克风→HTTPS            |
| 终端侧 RTP         | 收流 10003/31000-31500（已暴露） | sendRtp 出站       | sendRtp + 收流（同一套，已就绪） |

**瓶颈只在"浏览器 → ZLM"这一段**：拉视频那段是 HTTP（已配），喊话/对讲那段是 WebRTC（没配）。终端侧三种业务都已就绪。

---

## 四、GB28181 协议与 WVP 代码的交叉实证

> 结论：**对讲（Talk）按 GB28181 本就是双向的；WVP 代码用 `broadcastMode` 区分喊话/对讲，并用 `a=sendrecv` 落实了对讲的双向性。**

### 4.1 GB28181 协议侧

GB28181-2016 把平台与终端之间的语音业务明确分为两类：

- **语音广播（Broadcast）**：平台向终端的单向语音传送。平台发、终端放。
- **语音对讲（Talk）**：平台与终端之间的**双向**语音对讲。双方都能说、都能听。

因此"平台浏览器既是生产者（说）又是消费者（听）"是对讲的固有要求，不是实现选择。

### 4.2 WVP 代码侧

**① `broadcastMode` 区分两种业务**（`PlayServiceImpl.audioBroadcast`）：

```java
String app = broadcastMode ? MediaStreamUtil.GB28181_BROADCAST : MediaStreamUtil.GB28181_TALK;
//   broadcastMode=true  → app="broadcast"（喊话，单向）
//   broadcastMode=false → app="talk"     （对讲，双向）
```

前端 `channelPlayer/index.vue` 用单选框让用户选"喊话(Broadcast)/对讲(Talk)"，默认 `broadcastMode=true`（喊话）。

**② 流到达后按 app 分流到不同命令**（`PlayServiceImpl.onApplicationEvent(MediaArrivalEvent)`）：

```java
if (GB28181_BROADCAST.equals(app)) { audioBroadcastCmd(...); }   // 喊话：单向广播流程
else if (GB28181_TALK.equals(app)) { talkCmd(...); }             // 对讲：双向对讲流程
```

**③ 对讲的 INVITE 用 `a=sendrecv`（双向）**（`SIPCommander.talkStreamCmd`）：

```java
content.append("m=audio " + sendRtpItem.getPort() + " TCP/RTP/AVP 8\r\n");
content.append("a=setup:passive\r\n");
content.append("a=connection:new\r\n");
content.append("a=sendrecv\r\n");                  // ← 双向：平台既发又收
content.append("a=rtpmap:8 PCMA/8000\r\n");
```

对比：实时点播的 INVITE 用的是 `a=recvonly`（平台只收视频），即 `SIPCommander` 中多处出现的 `content.append("a=recvonly\r\n")`。两者方向性截然不同。

**④ `talk()` 同时建立发/收两路**（`PlayServiceImpl.talk`）：

```java
// 发流：浏览器麦克风 → ZLM → 终端
mediaServerService.startSendRtpPassive(mediaServerItem, sendRtpInfo, ...);
// 收流：终端麦克风 → ZLM，落到单独的接收流，供浏览器播放
sendRtpInfo.setReceiveStream(stream + "_talk");
receiveRtpServerService.addAuthenticateInfoForGb28181Talk(mediaServerItem, sendRtpInfo.getStream());
```

发流（`startSendRtpPassive`）承载浏览器麦克风音频去终端；收流（`receiveStream = stream + "_talk"`）承接终端回传的麦克风音频，供浏览器消费。**两路并存 = 对讲的双向性。**

---

## 五、为什么喊话/对讲必须走 WebRTC，不能走 WebSocket

浏览器要把麦克风音频**低延迟地主动推给媒体服务器**，标准且可用的通道只有 WebRTC。四个硬性原因：

### 5.1 延迟：喊话/对讲是实时的，WebSocket 扛不住

喊话/对讲要求单程延迟一般 < 200~300ms，否则没法用。

- **WebSocket 跑在 TCP 上**，有队头阻塞：丢一个包，后面全卡住等重传。对实时音频，"重传一个已过时的包"比"直接丢"更糟。
- WS 推音频一般用 `MediaRecorder` 出块（100ms~1s 一块），实测 0.5~2s 延迟。
- **WebRTC 跑在 UDP 上**：无队头阻塞，丢包就丢（交给抖动缓冲 / PLC），自带自适应码率、FEC、NACK，能做到一两百毫秒。

### 5.2 编码：终端要 G.711，WebRTC 原生就支持

GB28181 终端收音频只认 **RTP + G.711A（PCMA）**。

- **WebRTC 强制支持 G.711（PCMA/PCMU）**（W3C/IETF 必选音频编码），浏览器可直接推 PCMA 给 ZLM，ZLM 几乎不用转码即可送给终端。
  （对应：`audioBroadcast` 里 `setCodec("G.711")`；对讲 INVITE 里 `a=rtpmap:8 PCMA/8000`；ZLM 日志 `Got track: PCMA`。）
- **WS 推流没有标准编码协商**，`MediaRecorder` 出的是 WebM/Opus 容器，ZLM 还得解码再重编码，又慢又费事，且无标准协议可循。

### 5.3 浏览器侧：WebRTC 是"实时推流"的唯一标准答案

浏览器里"抓麦克风 → 编码 → 实时发送"这套**完整的、跨浏览器统一的标准**就是 WebRTC。WebSocket 只是个字节流管道，上面没有标准的低延迟音频推流协议；要用 WS 推音频等于自己发明私有协议，无生态支持。

### 5.4 ZLM 的入口：浏览器能往 ZLM 推实时媒体的唯一标准通道就是 WebRTC

ZLM 提供给浏览器"推流"的入口（`/index/api/webrtc`、WHIP）就是 WebRTC。浏览器不会说 RTMP 之类的老推流协议，所以唯一能往 ZLM 推实时媒体的标准通道就是 WebRTC。

> 对讲的"消费者"方向（浏览器播放对方音频）协议上既可走 WebRTC 也可走 WS-FLV；但"生产者"方向（推麦克风）必然是 WebRTC，所以只要麦克风这步通不了，对讲就起不来。

---

## 六、那拉视频能不能用 WebRTC？会比 WebSocket 差吗？

**能，而且效果不会更差，延迟还更低。** 这里有个常见误解，拆清楚：

- WVP 本来就支持 WebRTC 播视频：播放器有 WebRTC tab，流信息里有 `rtc`（`type=play`）地址。所以"视频走 WebRTC"是现成可选项。

从效果（流畅度 / 延迟 / 抗弱网）看，WebRTC 视频 **≥** WS-FLV：

| 维度       | WebRTC                | WS-FLV（Jessibuca / h265web） | HLS   |
| -------- | --------------------- | --------------------------- | ----- |
| 端到端延迟    | **~200-500ms**        | ~1-3s（可调到 500ms-1s）       | 5-30s |
| 抗丢包 / 弱网 | 强（UDP+FEC+NACK+自适应码率） | 一般（TCP 丢包会卡）              | 差     |
| 画质（同码率）  | 相当（都 H264）            | 相当                         | 相当    |

> **注**：Jessibuca 和 h265web 使用**相同的 WS-FLV 协议**（`ws://host:port/rtp/stream.live.flv`），只是解码侧重不同——Jessibuca 主打 H.264，h265web 额外支持 H.265（HEVC）。协议性能上两者没有区别。

延迟上 WebRTC 明显占优。**论性能，WebRTC 不输、甚至更好。**

**那为什么默认还是用 WebSocket 拉视频？不是性能原因，是"部署和运维成本"：**

|                   | WS-FLV          | WebRTC                          |
| ----------------- | --------------- | ------------------------------- |
| 需要的基础设施           | 只要 HTTP / nginx | UDP 端口 + ICE + STUN + **HTTPS** |
| 穿防火墙 / NAT / 公司代理 | 很容易（单 TCP 口）    | 经常被挡（要 TURN 兜底）                 |
| nginx 反代          | 直接支持            | 媒体是 UDP，**nginx 反代不了**          |
| 服务器每连接开销          | 轻（一个 WS 流）      | 重（DTLS/SRTP 加密 + ICE）           |
| 大规模并发 / CDN       | 好（HTTP 可缓存）     | 难（点对点，难上 CDN）                   |

对 GB28181 监控场景：看的是"发生了什么"，1-3 秒延迟无所谓；但又希望随便部署、穿各种网络、nginx 一反代就行。所以默认选了"延迟够用、但部署极简"的 WS-FLV。**这不是因为 WS 更好，而是因为监控对延迟不敏感、省事更重要。**

> 结论：**WS 是"够用且省事"，WebRTC 是"更强但要花钱配基础设施"。** 喊话/对讲之所以只能用 WebRTC，是因为它们要浏览器主动推低延迟音频，没有"省事的 WS 方案"可选。
>
> **实测印证**：在本案例部署完成后实测三种播放方式，WebRTC 拉视频的流畅度和延迟表现确实优于 Jessibuca（WS-FLV）和 h265web——与本节的分析一致。如果您的部署场景对延迟敏感且基础设施允许，建议优先选用 WebRTC 播放视频。

---

## 七、为什么当前配置：视频通、喊话/对讲不通（实测证据）

### 7.1 端口映射 + sub_filter 不是原因（实测）

用 admin 账号请求同一个对讲接口，两种取法对比：

| 取法               | 返回的 `rtc` 地址                                        |
| ---------------- | --------------------------------------------------- |
| 直连 WVP（`:18978`） | `http://192.168.0.40:80/index/api/webrtc?...`       |
| 经 nginx（`:8090`） | `http://192.168.0.40:**6080**/index/api/webrtc?...` |

**`sub_filter` 把喊话/对讲的 WebRTC 信令地址也正确改写成了 6080**，和视频一模一样。即 `80→6080` + sub_filter 这套对"视频"和"喊话/对讲信令"都是通的。端口映射不是失败的原因。

### 7.2 浏览器在发 SDP 之前就挂了（ZLM 日志零 WebRTC POST）

查 ZLM 整天日志，喊话/对讲时段（10:47-10:49、11:27、11:33 等）**浏览器没有向 ZLM 发过一次 `/index/api/webrtc` 信令 POST**（日志里该路径只有排查时人工测试的两次）。端口改写对（`:6080`），但浏览器的 SDP 推流请求没出现在 ZLM 日志里，背后是两个并存原因（详见 7.5）：其一，浏览器在非安全源（非 localhost 的明文 http）被禁止开麦克风，`getUserMedia` 失败 → 压根不发 offer；其二，配置里的 IP 已过期，`192.168.0.40` 现指向另一台机器（其 `:6080` 给空响应），即便浏览器发出 POST 也去了"死地址"，真实 ZLM 自然零 POST。

### 7.3 三个没配的 WebRTC 必要条件

| 条件                                    | 当前状态                                              | 后果                                  |
| ------------------------------------- | ------------------------------------------------- | ----------------------------------- |
| **HTTPS**（浏览器才允许 `getUserMedia` 开麦克风） | ❌ 纯 HTTP（polaris-nginx 无 SSL、宿主无 443）             | 浏览器在非安全源禁止访问麦克风 → 不发 offer → 零 POST |
| **WebRTC 媒体口 UDP 8000 暴露**            | ❌ docker 未映射，且宿主 8000 已被 `security-management` 占用 | 即使信令通，SRTP 媒体也到不了 ZLM               |
| **`externIP`**                        | ❌ ZLM `config.ini` 中 `externIP=` 为空               | ICE candidate 是容器内网 IP，浏览器不可达       |

喊话/对讲的"生产者"方向（推麦克风）依赖这三样，视频走 HTTP/WS 不依赖，所以视频照常工作、喊话/对讲不行。对讲额外的"消费者"方向（听对方）即便单独能通，也救不了被卡在生产者这一步的整条链路。

### 7.4 终端侧不是瓶颈

收视频用的 RTP 端口（`10003` / `31000-31500`）和喊话/对讲的发音频（sendRtp，出站）/ 收音频用的是同一套、均已就绪。瓶颈只在"浏览器 → ZLM"这一段：拉视频那段是 HTTP（已配），喊话/对讲那段是 WebRTC（没配）。

### 7.5 补充发现：IP 过期 + localhost 免 HTTPS（修正 7.3 初判）

后续核实补充两点：

1. **IP 已过期**：本机局域网 IP 已由 DHCP 从 `192.168.0.40` 变为 `192.168.0.24`，而 `.env`/DB 仍是 `.40`（`.40` 现指向网上另一台机器，其 `:6080` 给空响应）。实测 `localhost:6080` → `200`，`192.168.0.40:6080` → 失败。
   
   这里需要区分两种访问途径：
   
   - 若通过 **`http://localhost:8090`** 访问，WVP 返回的流 URL 中 `localhost:6080` 仍可达本机 ZLM，因此**视频拉流正常**——这与第一节的观察一致。
   - 若通过 **`http://192.168.0.40:8090`** 访问，流 URL 中的 `192.168.0.40:6080` 指向了另一台机器，视频也会断。
   
   也就是说，喊话/对讲不走通有两层原因：一是 WebRTC 缺 HTTPS/媒体口/externIP（即使 localhost 也走不通）；二是即使 HTTPS/媒体口配好，访问地址用过期 LAN IP 的话，信令 POST 仍会打到"死地址"。IP 过期是"零 WebRTC POST"的另一主因，不只是 HTTPS。设备侧 `SIP_ShowIP=.40` 过期也是终端"消息超时未回复"的根因之一。

2. **localhost 是安全上下文**：`http://localhost`（任意端口）被浏览器视为可信，`getUserMedia` 放行。所以 7.3 表里"纯 HTTP → 麦克风被禁"只对**非环回**的 http（如 `http://192.168.0.40:8090`）成立；若用 `http://localhost:8090` 访问，麦克风不会被拦，**无需 HTTPS**。这是第八节"在 HTTP 下跑通"方案的前提。

---

## 八、配置修改步骤：在 HTTP 下跑通喊话/对讲（不影响视频拉流）

核心思路：**在平台本机用 `http://localhost:8090` 访问**——localhost 是浏览器的安全上下文，麦克风放行，**无需 HTTPS**；再把"浏览器侧地址"和"终端侧地址"分开配，补齐 WebRTC 媒体口（信令跨源由 ZLM `allow_cross_domains=1` 放行，**无需改 nginx**）。全程是"加法"，不动视频那条 HTTP/WS/6080 的路。

> 背景发现：排查中确认本机局域网 IP 已由 DHCP 从 `192.168.0.40` 变为 `192.168.0.24`，而 `.env`/DB 里的 IP 仍是 `.40`（已指向网上另一台机器）。所以"喊话/对讲地址不对"的根因之一就是 **IP 过期**，下面步骤会一并修掉。建议长期给平台主机设**静态 IP**，避免 DHCP 漂移。

### 8.1 配置源头：`docker/.env`（最先改，必须重建容器）

`application-docker.yml` 用占位符直接读 `.env`——`media.stream-ip: ${Stream_IP}`、`media.sdp-ip: ${SDP_IP}`、`sip.show-ip: ${SIP_ShowIP}`；nginx 模板 `set $original_host ${Stream_IP};` 也读同一个变量。**`.env` 是总开关**，改后须用 `docker compose up -d` **重建容器**（`docker restart` 不会刷新环境变量）。

| 项            | 旧值（过期 IP） | → 新值（当前 LAN IP） | 为什么                                                                     |
| ------------ | -------------- | ------------------ | ----------------------------------------------------------------------- |
| `Stream_IP`  | `192.168.0.40` | `192.168.0.24`     | 给浏览器生成流 URL。改为当前正确 IP；注意此 IP 只影响流 URL 内容，**与 getUerMedia 安全上下文无关**（安全上下文由浏览器访问地址 `http://localhost:8090` 决定） |
| `SDP_IP`     | `192.168.0.40` | `192.168.0.24`     | 写进终端 SIP SDP 的 IP，终端靠此 IP 推拉流。必须真实可达的 LAN IP                             |
| `SIP_ShowIP` | `192.168.0.40` | `192.168.0.24`     | SIP 信令中向终端宣告的 IP。不改则终端发信令到过期 IP，导致消息超时                               |

> **重要经验**：`Stream_IP` **不需要改为 `localhost`**——`http://localhost` 的安全上下文是由**浏览器访问地址**决定的，与 WVP 返回的流 URL 中写什么 IP 无关。Stream_IP、SDP_IP、SIP_ShowIP 都填当前局域网 IP，WVP 和 nginx 两边的 `$original_host` 自动一致，WS-FLV 和 WebRTC 信令都能正常工作。`.env` 其他行（`MediaRtp`/`WebHttp`/`SIP_Port` 等）不动。

### 8.2 ZLM WebRTC 媒体口：`docker/media/config.ini` 的 `[rtc]` 段

| 项          | 旧值     | → 新值           | 为什么                                    |
| ---------- | ------ | -------------- | -------------------------------------- |
| `port`     | `8000` | `18000`        | 宿主 8000 被 `security-management` 占用，必须换 |
| `tcpPort`  | `8000` | `18000`        | TCP 兜底端口同步换                            |
| `externIP` | (空)    | `192.168.0.24` | ICE 对外宣告 IP，本机浏览器可达                    |

> **注意**：`config.ini` 是挂载到容器内的文件，修改后 ZLM **不会自动重新读取**，必须重启 ZLM 容器：`docker restart docker-polaris-media-1`。用 `docker compose up -d` 也不会重读（容器已在运行），必须显式 restart。

### 8.3 暴露新端口：`docker/docker-compose.yml` 的 `polaris-media.ports`

新增两行（紧跟 `"6080:80/tcp"` 那组）：

```yaml
- "18000:18000/udp"    # WebRTC SRTP 媒体（喊话/对讲音频）
- "18000:18000/tcp"    # UDP 不通时的 TCP 兜底
```

### 8.4 nginx 无需改动（CORS 已由 ZLM 处理）

排查中核实：ZLM 的 `docker/media/config.ini` `[http]` 段已有 `allow_cross_domains=1`（第 76 行），开启后 ZLM 会在 HTTP 响应里加 `Access-Control-Allow-Origin: *`，并处理 OPTIONS 预检。因此 WebRTC 信令**不需要 nginx 同源代理**。

进一步看客户端 `web/public/static/js/ZLMRTCClient.js`：浏览器推 SDP offer 的 POST 用的是 `Content-Type: text/plain`、不带凭据（其内置的 axios 未设 `withCredentials`）——属于 CORS"简单请求"，浏览器**不发 OPTIONS 预检**，直接发 POST。所以 `localhost:8090` 的页面跨源 POST 到 `localhost:6080/index/api/webrtc`，在 `allow_cross_domains=1` 下直接成功。**nginx 一行都不用动**；UDP 媒体仍直连 `192.168.0.24:18000`。

> 说明：早先版本曾在此建议新增 `location = /index/api/webrtc` 反代 + 一条 `sub_filter` 来"绕 CORS"，前提是"ZLM 无 CORS 开关"。核实后该前提不成立（开关 `allow_cross_domains` 存在且 `=1` 已开），故该代理层撤销——既无 CORS 收益、也无"省预检"收益（本就是简单请求、无预检）。

### 8.5 生效与验证

1. 重建容器：

```bash
cd docker
docker compose up -d
docker restart docker-polaris-media-1   # 确保挂载的 config.ini 被 ZLM 重新读取
```

2. 核对 DB 是否随 `.env` 刷新（没刷新就手动改）：

```sql
SELECT stream_ip, sdp_ip FROM wvp.wvp_media_server;   -- 期望: localhost / 192.168.0.24
-- 仍是旧值则:
UPDATE wvp.wvp_media_server SET stream_ip='localhost', sdp_ip='192.168.0.24';
```

3. 在**平台本机**打开 `http://localhost:8090` → 设备接入 → 视频播放 → 对讲。
4. F12 → Network：浏览器直连 `POST localhost:6080/index/api/webrtc?...type=push` 返回 **200**（CORS 由 `allow_cross_domains` 放行，无需代理）；Console 无 `getUserMedia` 报错。
5. ZLM 日志出现 `/index/api/webrtc` + `broadcast/` 或 `talk/` 流注册；终端 5331 出声。

### 8.6 为什么这样就能 HTTP 跑通

| 拦路虎           | 本方案如何过                                                                           |
| ------------- | -------------------------------------------------------------------------------- |
| 麦克风要 HTTPS    | `http://localhost` 是安全上下文，麦克风放行 → **不用 HTTPS**                                   |
| 终端要真实 IP      | `SDP_IP` / `SIP_ShowIP` = `192.168.0.24`（终端走局域网）                                 |
| WebRTC 媒体口没暴露 | 暴露 `18000/udp` + `externIP=192.168.0.24`                                         |
| WebRTC 信令跨源   | ZLM `allow_cross_domains=1` 已返回 `Access-Control-Allow-Origin: *`，客户端是简单请求 → 无需代理 |

> 适用范围：此方案要求**在平台本机**用 `http://localhost:8090` 访问。`Stream_IP` 保持局域网 IP（`192.168.0.40`），不需要改为 `localhost`——安全上下文只取决于浏览器访问地址，与流 URL 中含什么 IP 无关。**跨机访问**需启用 HTTPS（非环回的 http:// 不是安全上下文，麦克风会被禁）；上 HTTPS 后页面是 https 而 ZLM 仍是 http，会触发**混合内容（mixed content）拦截**，届时需用 nginx 代理终结 TLS 转发到 ZLM、或给 ZLM 启用 https（`config.ini` 已留 `sslport=443`）。完整配置步骤见第九章"跨机生产部署：HTTPS + WebRTC 完整配置"。

### 8.7 各配置项详解（是什么 / 为什么改 / 不改的后果）

#### A. `docker/.env`（配置总开关；经 `application-docker.yml` 占位符注入 WVP，并驱动 nginx）

**`Stream_IP`（`192.168.0.40` → `192.168.0.24`）**

- **是什么**：浏览器侧流地址的主机名。经 `media.stream-ip: ${Stream_IP}` 决定 WVP 生成的所有"给浏览器"的 URL（拉视频 `ws-flv`、喊话/对讲 `rtc` 推流）用什么主机；nginx 模板 `set $original_host ${Stream_IP}` 也用它做 `sub_filter` 匹配。
- **为什么改**：DHCP 将本机 IP 从 `.40` 变更为 `.24`，流 URL 须指向当前正确的局域网 IP。
- **不改的后果**：浏览器拿到过期 IP，WS-FLV 和 WebRTC 都连不上。
- **注意**：**不要改为 `localhost`**——`http://localhost` 的 getUserMedia 安全上下文取决于**浏览器访问地址**，与流 URL 内容无关。Stream_IP 填 LAN IP 即可（如 `192.168.0.24`），sub_filter 自动将端口 `:80` 改写为 `:6080`，浏览器直连本机 6080 到 ZLM。

**`SDP_IP`（`192.168.0.40` → `192.168.0.24`）**

- **是什么**：写进给终端的 SIP SDP `c=IN IP4` 的 IP（`media.sdp-ip`），告诉终端"媒体服务器在哪、RTP 推到哪/从哪拉"。
- **为什么改**：`.40` 过期，终端连不到平台。终端是独立设备，必须用平台在局域网里真实可达的 IP。
- **不能填 localhost**：终端的 `127.0.0.1` 是它自己，会连到终端自身。
- **不改的后果**：终端侧 RTP 链路建立不了，拉视频/对讲的媒体全断。

**`SIP_ShowIP`（`192.168.0.40` → `192.168.0.24`）**

- **是什么**：SIP 服务器对设备宣告的 IP（`sip.show-ip`），出现在 SIP Contact/Via/Record-Route 里，设备据此回信、续订。
- **为什么改**：设备按 `.40` 找平台找不到，正是终端"消息超时未回复"的根因之一。
- **不能填 localhost**：同 `SDP_IP`，设备连不到。
- **不改的后果**：设备注册、目录/报警订阅等 SIP 信令不稳或失败。

#### B. `docker/media/config.ini` 的 `[rtc]` 段（只影响"浏览器 ↔ ZLM"这段 WebRTC，与终端无关）

**`port`（`8000` → `18000`）**

- **是什么**：ZLM 的 WebRTC **媒体** UDP 监听端口，承载 STUN/DTLS/**SRTP**——对讲音频的实际字节流走这里。**不是信令端口**（信令走 HTTP `/index/api/webrtc`）。
- **为什么改**：宿主机 `8000` 已被 `security-management` 占用，docker 无法再映射 `8000:8000`。
- **不改的后果**：要么端口冲突映射失败，要么 WebRTC 媒体口没暴露，浏览器 SRTP 到不了 ZLM。

**`tcpPort`（`8000` → `18000`）**

- **是什么**：WebRTC 媒体的 TCP 兜底端口（UDP 被防火墙挡时用 TCP 传同样的媒体）。
- **为什么改**：与 `port` 保持一致，且同样需要 docker 映射。
- **不改的后果**：UDP 不通时无兜底（影响一般不大，但保持一致更稳）。

**`externIP`（空 → `192.168.0.24`）**

- **是什么**：ZLM 在 ICE candidate 里**宣告给客户端**的 IP（"来这个 IP 找我"），**不是 bind 的 IP**（ZLM 实际 bind `0.0.0.0`）。
- **为什么改**：置空时 ZLM 自动取网卡 IP，在 docker 里很可能取到容器内网 IP（`172.18.0.x`），浏览器不可达。填 `192.168.0.24`：本机 localhost 浏览器能访问到（同一台机），candidate 可达。
- **不改的后果**：浏览器拿到不可达的 candidate，即便信令通了，WebRTC 媒体仍连不上。

#### C. `docker/docker-compose.yml` 的 `polaris-media.ports`（新增 `18000:18000/udp` + `18000:18000/tcp`）

- **是什么**：把容器内 ZLM 的 `18000` 映射到宿主机 `18000`，让浏览器能从宿主侧连到 ZLM 的 WebRTC 媒体口。
- **为什么必须 `18000:18000`（对外=对内）**：ZLM 在 candidate 里写 `externIP:18000`，浏览器连 `192.168.0.24:18000`，docker 必须把宿主 18000 映射到容器 18000 才对得上（ZLM 注释明确要求"NAT 部署时外网映射端口须与本端口一致"）。
- **为什么原来没这行**：原部署只服务视频（HTTP/WS），根本没用 WebRTC 媒体口，所以没映射。
- **不改的后果**：浏览器连不到 ZLM 的 18000，WebRTC 媒体失败。

#### D. `docker/nginx/templates/nginx.conf.template`（**无需改动**）

- **不改的原因**：WebRTC 信令的跨源已由 ZLM 自身解决——`config.ini` `[http]` 段 `allow_cross_domains=1`（第 76 行）会返回 `Access-Control-Allow-Origin: *`，且客户端 `ZLMRTCClient.js` 用 `Content-Type: text/plain` 发送属 CORS 简单请求、无预检（详见 8.4）。因此**不需要**新增 `location = /index/api/webrtc` 反代。
- **既有规则照常**：`Stream_IP` 设为当前 LAN IP（如 `192.168.0.24`），既有 `sub_filter`（`http://$original_host:80/`→`http://$original_host:6080/`）会把推流 URL 变成 `http://192.168.0.24:6080/...`，浏览器直连该地址、CORS 放行即可；也**不需要**新增 `sub_filter` 规则。

---

## 九、跨机生产部署：HTTPS + WebRTC 完整配置

第八章的方案适用于**平台本机 localhost 调试**，`Stream_IP` 保持局域网 IP。若需从**其他机器**访问（值班台、指挥中心等），核心差异只在于**是否使用 HTTPS**，其他配置（`Stream_IP`、`SDP_IP` 等）保持不变。上 HTTPS 后新增的约束有两个：

1. 页面是 `https://` 而 ZLM 信令是 `http://` 时，浏览器会拦截**混合内容（mixed content）**——需让 WebRTC 信令也走 HTTPS
2. 需要 nginx 上 HTTPS 并为 ZLM 信令做 TLS 终结代理，WebRTC 媒体口和终端侧地址不变

### 9.1 对比：两个方案的核心差异

| 维度 | localhost 调试方案（第八章） | 生产 HTTPS 方案 |
|------|------------------------------|-----------------|
| 访问地址 | `http://localhost:8090` | `https://192.168.0.24:8443`（或 443） |
| `Stream_IP` | `192.168.0.24`（局域网 IP） | `192.168.0.24`（**不变**） |
| `SDP_IP` / `SIP_ShowIP` | `192.168.0.24` | `192.168.0.24`（**不变**） |
| nginx 协议 | HTTP（无 SSL） | HTTPS（需证书） |
| 视频拉流 URL | `ws://192.168.0.24:6080/rtp/...` | `wss://192.168.0.24:8443/rtp/...`（自动切换） |
| WebRTC 信令 URL | `http://192.168.0.24:6080/index/api/webrtc`（直连 ZLM，CORS 放行） | `https://192.168.0.24:8443/index/api/webrtc`（走 nginx 代理解决混合内容） |
| WebRTC 媒体口 | `192.168.0.24:18000/udp` | `192.168.0.24:18000/udp`（**不变**，直连） |
| externIP | `192.168.0.24` | `192.168.0.24`（**不变**） |
| DB 记录 stream_ip | `192.168.0.24` | `192.168.0.24`（**不变**） |

### 9.2 前提条件

- 平台主机有**静态 IP**（本例 `192.168.0.24`），避免 DHCP 再次漂移
- 已有一份 SSL 证书（生产用 CA 签发证书，测试可用自签名证书）
- 已在宿主防火墙放行 `443/8443`（HTTPS）和 `18000/udp`（WebRTC 媒体）

### 9.3 步骤一：更新 `.env`（配置总开关）

```dotenv
Stream_IP=192.168.0.24       # 保持局域网 IP（与第八章一致，不变）
SDP_IP=192.168.0.24          # 给终端 SDP 的 IP（不变）
SIP_ShowIP=192.168.0.24      # 给终端 SIP 的 IP（不变）
```

`Stream_IP` 保持局域网 IP，WVP 生成的所有流 URL 都以 `192.168.0.24` 为主机名，其他机器才能正确连接。

### 9.4 步骤二：nginx HTTPS 配置 + ZLM 信令代理

`docker/nginx/templates/nginx.conf.template` 需要做三件事：

**① 添加 HTTPS server 块（监听 8443）**：

```nginx
server {
    listen 8443 ssl;
    server_name 192.168.0.24;

    ssl_certificate     /etc/nginx/ssl/server.crt;     # 挂载证书文件
    ssl_certificate_key /etc/nginx/ssl/server.key;

    # 与 HTTP server 块相同的 location 配置...
    location /api/ {
        proxy_pass http://polaris-wvp:18978/;
        sub_filter_types *;
        sub_filter_once off;
        sub_filter "http://$original_host:80/"  "https://$original_host:8443/";
        sub_filter "ws://$original_host:80/"    "wss://$original_host:8443/";
        sub_filter "http://$original_host:6080/index/api/webrtc" "https://$original_host:8443/index/api/webrtc";
    }

    location /rtp/ {
        proxy_pass http://polaris-media:80/;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
    }

    # 新增：WebRTC 信令代理（解决混合内容）
    location = /index/api/webrtc {
        proxy_pass http://polaris-media:80/index/api/webrtc;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }
}
```

关键变化说明：

- `sub_filter` 改写规则新增 `http://`→`https://` 和 `:80`→`:8443` 的映射（因为 WVP 返回的 URL 中 `Stream_IP` 是 `192.168.0.24`，协议是 `http`，需要改成 `https://192.168.0.24:8443`）
- 新增 `location = /index/api/webrtc`：**这是生产方案必需的同源代理**，不是 CORS 原因，而是**混合内容拦截**——HTTPS 页面不能发 HTTP 请求。客户端的 `ZLMRTCClient.js` 会从 streamInfo 拿到信令 URL（经 `sub_filter` 改写后为 `https://192.168.0.24:8443/index/api/webrtc`），通过 nginx 代理转发到 polaris-media 的 HTTP 信令端口
- 新增 `sub_filter` 行将 WebRTC 信令 URL 从 `http://192.168.0.24:6080/...` 改写为 `https://192.168.0.24:8443/index/api/webrtc`

**② 挂载 SSL 证书**：在 `docker/docker-compose.yml` 的 `polaris-nginx` 卷挂载中新增：

```yaml
- "./nginx/ssl:/etc/nginx/ssl"    # SSL 证书目录
```

将 `server.crt` 和 `server.key` 放入 `docker/nginx/ssl/`。

**③ 暴露 HTTPS 端口**：在 `docker/docker-compose.yml` 的 `polaris-nginx.ports` 中新增：

```yaml
- "8443:8443/tcp"          # HTTPS（也可用 443，需管理员权限）
```

### 9.5 步骤三：ZLM 配置（WebRTC 媒体口 + 端口映射）

与 8.2 和 8.3 **完全相同**，因为 WebRTC 媒体口（`18000/udp`）不依赖 HTTPS，直连即可：

- `docker/media/config.ini` `[rtc]` 段：`port=18000`、`tcpPort=18000`、`externIP=192.168.0.24`
- `docker/docker-compose.yml` `polaris-media.ports`：新增 `"18000:18000/udp"` 和 `"18000:18000/tcp"`

### 9.6 步骤四：DB 更新

`Stream_IP` 不变，但 DB 中 `stream_ip` 字段可能未正确同步，需手动核对：

```sql
UPDATE wvp.wvp_media_server SET stream_ip='192.168.0.24', sdp_ip='192.168.0.24';
```

### 9.7 步骤五：验证

1. 重建容器：
```bash
cd docker
docker compose up -d
docker restart docker-polaris-media-1
```

2. 在其他机器用浏览器打开 `https://192.168.0.24:8443`（注意是 HTTPS，浏览器会提示证书风险——自签名证书需手动信任）。

3. 测试视频拉流：
   - 分屏监控页面应能正常播放视频（WS-FLV → WSS-FLV 自动切换）
   - F12 → Network 检查流 URL 是否为 `wss://192.168.0.24:8443/rtp/...`

4. 测试喊话/对讲：
   - 点击对讲按钮，应正常调起麦克风授权（HTTPS 下浏览器不拦 `getUserMedia`）
   - F12 → Network 应出现 `POST https://192.168.0.24:8443/index/api/webrtc?...type=push` → **200**
   - ZLM 日志出现对应 `broadcast/` 或 `talk/` 流注册
   - 终端出声

5. 检查无混合内容警告：Console 无 `Mixed Content` 报错。

### 9.8 常见问题

**Q: 自签名证书导致页面提示不安全，影响对讲吗？**
A: 不影响。`getUserMedia` 只要求页面是安全上下文（HTTPS 即满足），不要求证书是 CA 签发。自签名证书首次访问时点"高级→继续前往"即可。但跨机访问的每个客户端都需要手动信任。

**Q: 为什么 WebRTC 信令不能像 WS-FLV 那样直接走 ZLM 的 HTTPS 端口？**
A: 技术上可行（ZLM 的 `config.ini` 有 `sslport=443`，填入证书路径即可开启 HTTPS），但 ZLM 的 HTTPS 端口和 nginx 的 HTTPS 端口需要避开冲突（443 已被宿主占用或 nginx 监听）。如果习惯集中管理证书、统一代理，nginx 代理方案更简单。若偏好端到端加密、ZLM 直连，可启用 ZLM 的 `sslport` 并将 `externIP` 对应端口映射出去——此时 `sub_filter` 不需改写协议，只需保证 ZLM 的 HTTPS 端口对外可达且 CORS 仍开。

**Q: 上 HTTPS 后，原本走 `ws_flv` 的视频流会自动切到 `wss_flv` 吗？**
A: 会。前端 `live/index.vue` 的代码按页面协议选择：`location.protocol === 'https:' ? data.wss_flv : data.ws_flv`。页面 HTTPS 时自动用 WSS。且 `sub_filter` 也已把 `ws://` 改写为 `wss://`，双重保障一致。

**Q: `18000/udp` 是必须暴露的，那防火墙需要开哪些端口？**
A: 最低要求：

| 端口 | 协议 | 用途 | 方向 |
|------|------|------|------|
| `8443` | TCP | HTTPS 页面 + nginx 代理信令 | 浏览器入站 |
| `18000` | UDP | WebRTC SRTP 媒体 | 浏览器入站 |
| `18000` | TCP | WebRTC 媒体 TCP 兜底 | 浏览器入站 |

视频 WS-FLV 已通过 nginx HTTPS 端口（8443）代理，不需额外端口。

---

## 十、关键配置事实索引

| 事实                     | 值 / 状态                                                                                                              | 来源                                             |
| ---------------------- | ------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------- |
| ZLM 容器内 HTTP 端口        | 80                                                                                                                  | `docker/media/config.ini` `[http] port=80`     |
| ZLM HTTP 对外映射          | 主机 `6080` → 容器 80                                                                                                   | `docker-compose.yml` `6080:80/tcp`             |
| ZLM CORS（HTTP 跨源）      | `allow_cross_domains=1`（`[http]` 段，已开启，返回 `Access-Control-Allow-Origin: *`）                                         | `docker/media/config.ini` 第 76 行               |
| 宿主机 80 端口占用            | IIS / Windows System（PID 4），非 ZLM                                                                                   | `netstat`                                      |
| WVP media 记录           | `ip/stream_ip/sdp_ip=192.168.0.40`，`http_port=80`                                                                   | `wvp.wvp_media_server` 表                       |
| sub_filter 改写规则        | `http://$original_host:80/` → `http://$original_host:6080/`、`ws://$original_host:80/` → `ws://$original_host:6080/` | `docker/nginx/templates/nginx.conf.template`   |
| 喊话/对讲 rtc URL（经 nginx） | `http://192.168.0.40:6080/index/api/webrtc?...type=push`                                                            | 实测（见 7.1）                                      |
| 喊话/对讲业务区分              | `broadcastMode=true→broadcast(单向)` / `false→talk(双向)`                                                               | `PlayServiceImpl.audioBroadcast`               |
| 对讲 INVITE SDP 方向       | `a=sendrecv`（双向）                                                                                                    | `SIPCommander.talkStreamCmd`                   |
| 点播 INVITE SDP 方向       | `a=recvonly`（单向，平台只收）                                                                                               | `SIPCommander`（多处）                             |
| ZLM `[rtc] port`       | `8000`（udp/tcp）                                                                                                     | `docker/media/config.ini`                      |
| WebRTC UDP 8000 暴露     | ❌ 未映射；宿主 8000 被 security-management 占用                                                                              | `docker port docker-polaris-media-1`           |
| ZLM `externIP`         | 空                                                                                                                   | `docker/media/config.ini` `externIP=`          |
| 前端访问协议                 | 纯 HTTP（polaris-nginx 无 SSL）                                                                                         | nginx 配置 + 实测                                  |
| 终端 RTP 收流端口            | `10003` / `31000-31500`（已暴露）                                                                                        | `docker-compose.yml`                           |
| **配置 IP 总开关**          | `docker/.env` 的 `Stream_IP`/`SDP_IP`/`SIP_ShowIP` → 经 `application-docker.yml` 占位符注入 WVP + nginx                    | `docker/.env`、`docker-compose.yml` environment |
| **本机当前局域网 IP**         | `192.168.0.24`（曾为 `.40`，DHCP 漂移；`.env`/DB 仍存 `.40` 已过期）                                                             | `ipconfig` 实测                                  |
| **浏览器安全上下文**           | `http://localhost`（任意端口）免 HTTPS；非环回 http 需 HTTPS                                                                    | 浏览器 Secure Context 策略                          |

---

## 十一、附：浏览器"安全上下文"为何要求 HTTPS

浏览器只允许"可信来源"的网页调用摄像头 / 麦克风这类高权限 API（`getUserMedia` 标注 `[SecureContext]`）。

- **算安全上下文**：`https://` / `wss://`、`http://localhost` / `127.0.0.1`、`file://`。
- **不算**：非 localhost 的明文 `http://`（如 `http://192.168.0.40:8090`）。
- **为什么**：HTTP 明文可被中间人（MITM）截获 / 篡改。若 HTTP 网页能开麦克风，MITM 可注入 JS 静默录音外传，用户无感知，且无法验证页面真伪。HTTPS 提供加密 + 完整性 + 身份认证，浏览器才敢放权（之上还会再弹一次用户授权框）。
- **结果**：拉视频只读数据、无需高权限 API，HTTP 下正常；喊话/对讲要写麦克风，HTTP 下被浏览器掐掉。任何"网页采集麦克风 / 摄像头"的功能（不止 WVP）都必须 HTTPS——这是浏览器层面的硬性要求，与端口映射、ZLM 无关。
