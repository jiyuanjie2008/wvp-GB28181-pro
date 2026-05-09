# 国标设备点播端到端完整流程

本文档基于对 WVP-GB28181-Pro 源码和 Docker 部署配置的逐行分析，梳理出国标设备（IPC/NVR）实时视频点播的完整端到端流程。

---

## 一、系统部署架构

### 1.1 容器与网络

所有服务运行在 Docker bridge 网络 `media-net` 上，通过 Docker 内部 DNS（服务名）互相访问。

```
主机 (192.168.0.40)
┌─────────────────────────────────────────────────────────────────┐
│                                                                 │
│  ┌──────────────────────┐                                       │
│  │  polaris-nginx       │  :8080 (对外暴露)                      │
│  │  ├─ /              → 静态前端 SPA                              │
│  │  ├─ /api/          → polaris-wvp:18978                       │
│  │  ├─ /record_proxy/ → polaris-wvp:18978                       │
│  │  ├─ /mediaserver/  → polaris-media:80                        │
│  │  ├─ /rtp/          → polaris-media:80 (支持 WebSocket)        │
│  │  └─ /mp4_record/   → polaris-media:80 (支持 WebSocket)        │
│  └──────────────────────┘                                       │
│                                                                 │
│  ┌──────────────────────┐                                       │
│  │  polaris-wvp         │  :18978 (HTTP API)                    │
│  │                      │  :8160   (SIP TCP/UDP)                │
│  │  ├─ Redis → polaris-redis:6379                                │
│  │  ├─ MySQL → polaris-mysql:3306                                │
│  │  └─ ZLM   → polaris-media:80  (HTTP API + Hook)              │
│  └──────────────────────┘                                       │
│                                                                 │
│  ┌──────────────────────┐                                       │
│  │  polaris-media (ZLM) │  :10001 RTMP (对外暴露)                │
│  │  HTTP=80 (仅内部)     │  :10002 RTSP (对外暴露)                │
│  │  WebRTC=8000 (未暴露) │  :10003 RTP  (对外暴露)                │
│  └──────────────────────┘                                       │
│                                                                 │
│  ┌──────────────────────┐  ┌──────────────────────┐            │
│  │  polaris-redis :6379 │  │  polaris-mysql :3306 │            │
│  │  (不对外暴露)         │  │  (不对外暴露)         │            │
│  └──────────────────────┘  └──────────────────────┘            │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
                    bridge: media-net
```

### 1.2 关键配置参数（来自 `.env` 和 `application-docker.yml`）

| 参数 | 值 | 作用 |
|------|----|------|
| `Stream_IP` | `192.168.0.40` | WVP 生成的流地址中的 IP（浏览器可达） |
| `SDP_IP` | `192.168.0.40` | SIP INVITE SDP 中的 IP（设备推流目标） |
| `SIP_Port` | `8160` | SIP 信令端口 |
| `WebHttp` | `8080` | Nginx 对外端口，也是 WVP 配置的 `ws-flv-port` |
| `MediaRtmp` | `10001` | ZLM RTMP 端口 |
| `MediaRtsp` | `10002` | ZLM RTSP 端口 |
| `MediaRtp` | `10003` | ZLM RTP 收流端口（单端口模式） |
| `ZLM_HOST` | `polaris-media` | WVP 访问 ZLM 的内部地址 |
| `ZLM_HOOK_HOST` | `polaris-wvp` | ZLM 回调 WVP 的内部地址 |
| `media.httpPort` | `80` | ZLM 内部 HTTP 端口 |

### 1.3 关键设计决策

- **Nginx 是唯一外部入口**：浏览器只访问 `IP:8080`，所有 API 和媒体流都经 Nginx 代理。
- **ZLM HTTP 不对外暴露**：ZLM 的 80 端口仅在 Docker 内网可达，Nginx 通过 `polaris-media:80` 代理。
- **WVP 生成的流 URL 端口 = 8080**：`flv-port` 和 `ws-flv-port` 都设为 `${MediaHttp}` = `8080`，指向 Nginx。

---

## 二、点播端到端流程（11 步）

### 时序总览

```
浏览器         Nginx          WVP             Redis/MySQL     SIP信令         设备(IPC)       ZLM
  │              │              │                  │              │              │              │
  │──① GET /api/play/start ──→│                  │              │              │              │
  │              │──② 代理 ──→│                  │              │              │              │
  │              │              │──③ 查询设备/通道 ─→│             │              │              │
  │              │              │──④ 选择ZLM,分配SSRC ─→│          │              │              │
  │              │              │──⑤ 在ZLM创建RTP Server ─────────────────────────────────────→│
  │              │              │──⑥ 构造SDP,发送SIP INVITE ─────────────────→│              │
  │              │              │                  │              │──⑦ 200 OK──→│              │
  │              │              │──⑧ 发送ACK ──────────────────────────────→│              │
  │              │              │                  │              │              │──⑨ RTP推流──→│
  │              │              │                  │              │              │              │
  │              │              │←──⑩ on_stream_changed Hook ─────────────────────────────────│
  │              │              │──⑪ 生成流URL,返回 ─→│             │              │              │
  │              │←─⑫ 返回StreamContent ─│          │              │              │              │
  │←─⑬ JSON响应(所有协议URL) ─│          │              │              │              │
  │──⑭ ws://IP:8080/rtp/xxx.flv →│       │              │              │              │
  │              │──⑮ 代理WS到polaris-media:80 ─────────────────────────────────────────────→│
  │←════════════ 视频画面 ═══════════════════════════════════════════════════════════════════│
```

---

### 步骤 ①：浏览器发起点播请求

**触发方式**：用户在前端界面点击"播放"按钮。

**源码位置**：`web/src/api/play.js`

```javascript
export function play(deviceId, channelId) {
  return request({
    method: 'get',
    url: '/api/play/start/' + deviceId + '/' + channelId
  })
}
```

**实际请求**：

```
GET http://192.168.0.40:8080/api/play/start/35020000001320000001/35020000001320000002
```

请求到达 Nginx（`polaris-nginx:8080`）。

---

### 步骤 ②：Nginx 代理 API 到 WVP

**源码位置**：`docker/nginx/templates/nginx.conf.template` 第 16-43 行

```nginx
location /api/ {
    proxy_set_header Host $http_host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_pass http://polaris-wvp:18978;

    # URL 替换：将媒体下载/录像的绝对URL改为Nginx相对路径
    set $original_host ${Stream_IP};
    sub_filter "http://$original_host/index/api/downloadFile" "mediaserver/api/downloadFile";
    sub_filter "http://$original_host/mp4_record" "mp4_record";
    sub_filter_once off;
    sub_filter_types application/json;
}
```

Nginx 将请求转发到 `polaris-wvp:18978/api/play/start/.../...`。

---

### 步骤 ③：WVP PlayController 接收请求

**源码位置**：`PlayController.java:83-144`

```java
@GetMapping("/start/{deviceId}/{channelId}")
public DeferredResult<WVPResult<StreamContent>> play(HttpServletRequest request,
    @PathVariable String deviceId, @PathVariable String channelId) {
    // 1. 校验设备存在
    Device device = deviceService.getDeviceByDeviceId(deviceId);
    // 2. 校验通道存在
    DeviceChannel channel = deviceChannelService.getOne(deviceId, channelId);
    // 3. 创建异步结果（超时时间由 userSetting.playTimeout 控制）
    DeferredResult<WVPResult<StreamContent>> result = new DeferredResult<>(...);
    // 4. 定义回调
    ErrorCallback<StreamInfo> callback = (code, msg, streamInfo) -> { ... };
    // 5. 调用 playService
    playService.play(device, channel, callback);
    return result;
}
```

关键点：使用 `DeferredResult` 实现异步非阻塞响应，等待流就绪后才返回。

---

### 步骤 ④：PlayServiceImpl 核心编排

**源码位置**：`PlayServiceImpl.java:289-477`

`play()` 方法的核心流程：

```java
private SSRCInfo play(MediaServer mediaServer, Device device, DeviceChannel channel,
                      String ssrc, Boolean record, ErrorCallback<StreamInfo> callback) {
    // 4a. 检查是否已有进行中的点播（去重）
    InviteInfo inviteInfoInCatch = inviteStreamService.getInviteInfoByDeviceAndChannel(
        InviteSessionType.PLAY, channel.getId());
    if (inviteInfoInCatch != null && inviteInfoInCatch.getStreamInfo() != null) {
        // 已有流，直接返回
        callback.run(SUCCESS, "...", streamInfo);
        return;
    }

    // 4b. 选择 MediaServer（负载均衡）
    MediaServer mediaServerItem = getNewMediaServerItem(device);
    // 如果设备指定了 mediaServerId 且不是 "auto"，使用指定的；否则选负载最低的

    // 4c. 生成流 ID
    String streamId = String.format("%s_%s", device.getDeviceId(), channel.getDeviceId());
    // 例如: "35020000001320000001_35020000001320000002"

    // 4d. 在 ZLM 上创建 RTP Server 并分配 SSRC
    SSRCInfo ssrcInfo = receiveRtpServerService.openGbRTPServerForPlay(
        mediaServer, device, channel, ssrc, record, hookCallback);

    // 4e. 发送 SIP INVITE
    cmder.playStreamCmd(mediaServer, ssrcInfo, device, channel,
        okEvent, errorEvent, timeout);
}
```

---

### 步骤 ⑤：在 ZLM 上创建 RTP 收流服务

**源码位置**：`receiveRtpServerService.openGbRTPServerForPlay()`

WVP 调用 ZLM 的 REST API `openRtpServer`，在 ZLM 上创建一个 RTP 收流端口：

```
POST http://polaris-media:80/index/api/openRtpServer
{
    "port": 0,                    // 0 表示使用单端口模式（10003）
    "stream_id": "rtp/35020000001320000001_35020000001320000002",
    "ssrc": "0A000001",           // 分配的 SSRC
    "secret": "su6TiedN2rVAmBbIDX0aa0QTiBJLBdcf"
}
```

**单端口模式**（`rtp.enable=false`）：ZLM 在固定端口 `10003` 上收流，通过 SSRC 区分不同流。

**多端口模式**（`rtp.enable=true`）：ZLM 在 `30000-30500` 范围内为每路流分配独立端口。

当前配置使用**单端口模式**（`rtp.enable=false`）。

返回结果包含：

```json
{
    "port": 10003     // 收流端口号
}
```

---

### 步骤 ⑥：构造 SDP 并发送 SIP INVITE

**源码位置**：`SIPCommander.java:211-297`

WVP 构造 GB28181 标准的 SDP（Session Description Protocol）并通过 SIP INVITE 发送给设备。

**SDP 内容**（以 TCP 被动模式为例）：

```
v=0
o=35020000001320000001 0 0 IN IP4 192.168.0.40
s=Play
c=IN IP4 192.168.0.40
t=0 0
m=video 10003 TCP/RTP/AVP 96 97 98 99
a=recvonly
a=rtpmap:96 PS/90000
a=rtpmap:98 H264/90000
a=rtpmap:97 MPEG4/90000
a=rtpmap:99 H265/90000
a=setup:passive
a=connection:new
y=0A000001                        ← SSRC
```

**SDP 关键字段解析**：

| 字段 | 值 | 含义 |
|------|----|------|
| `o=` | `设备ID ... IN IP4 192.168.0.40` | 会话所有者，IP 为 `SDP_IP` |
| `c=` | `IN IP4 192.168.0.40` | 连接地址，设备推流的目标 IP |
| `m=` | `video 10003 TCP/RTP/AVP 96 97 98 99` | 媒体行：端口 `10003`，TCP/RTP 传输，支持 PS/H264/MPEG4/H265 |
| `a=recvonly` | — | WVP 只接收，不发送 |
| `a=setup:passive` | — | TCP 被动模式，ZLM 等待设备主动连接 |
| `y=` | `0A000001` | SSRC，用于流标识 |

**SIP INVITE 消息格式**：

```
INVITE sip:35020000001320000002@3502000000 SIP/2.0
Via: SIP/2.0/UDP 192.168.0.40:8160;branch=z9hG4bK...
From: <sip:35020000002000000001@3502000000>;tag=...
To: <sip:35020000001320000002@3502000000>
Call-ID: ...@192.168.0.40
CSeq: 1 INVITE
Content-Type: APPLICATION/SDP
Contact: <sip:35020000002000000001@192.168.0.40:8160>
Content-Length: ...

[SDP Body]
```

**传输模式**（由 `device.streamMode` 决定）：

| 模式 | SDP m 行 | 说明 |
|------|---------|------|
| `UDP` | `m=video 10003 RTP/AVP ...` | 设备通过 UDP 推送到 192.168.0.40:10003 |
| `TCP-PASSIVE` | `m=video 10003 TCP/RTP/AVP ...` + `a=setup:passive` | ZLM 监听 TCP，设备主动连接 |
| `TCP-ACTIVE` | `m=video 10003 TCP/RTP/AVP ...` + `a=setup:active` | ZLM 主动连接设备（需要多端口模式） |

---

### 步骤 ⑦：设备回复 200 OK

**源码位置**：`InviteResponseProcessor.java:63-88`

设备收到 INVITE 后，开始向 SDP 中指定的地址（`192.168.0.40:10003`）推送 RTP 媒体流，并返回 SIP 200 OK。

设备回复的 200 OK 中包含设备端 SDP，携带设备实际的媒体参数。

```java
// InviteResponseProcessor 处理 200 OK
public void process(ResponseEvent evt) {
    SIPResponse response = (SIPResponse) evt.getResponse();
    int statusCode = response.getStatusCode();

    if (statusCode == Response.OK) {
        // 解析设备返回的 SDP
        String contentString = new String(response.getRawContent());
        Gb28181Sdp gb28181Sdp = SipUtils.parseSDP(contentString);

        // 构造并发送 ACK
        Request reqAck = headerProvider.createAckRequest(...);
        sipSender.transmitRequest(..., reqAck);
    }
}
```

---

### 步骤 ⑧：WVP 发送 SIP ACK

WVP 收到 200 OK 后，立即发送 SIP ACK 给设备，完成三次握手（INVITE → 200 OK → ACK）。此时设备确认点播建立成功，开始持续推送 RTP 流。

```
ACK sip:35020000001320000002@192.168.1.100:5060 SIP/2.0
Via: SIP/2.0/UDP 192.168.0.40:8160;branch=z9hG4bK...
From: <sip:35020000002000000001@3502000000>;tag=...
To: <sip:35020000001320000002@3502000000>;tag=...
Call-ID: ...@192.168.0.40
CSeq: 1 ACK
Content-Length: 0
```

---

### 步骤 ⑨：设备推送 RTP 媒体流到 ZLM

设备根据 SDP 中的指示，将 RTP 媒体流推送到 ZLM：

```
IPC (192.168.1.100) → 192.168.0.40:10003 → Docker 10003:10003 → ZLM
```

- **传输协议**：根据 `device.streamMode` 决定 UDP 或 TCP
- **端口**：单端口模式下为 `10003`（`.env` 中 `MediaRtp=10003`）
- **SSRC**：`0A000001`，ZLM 通过 SSRC 将流关联到 `rtp/35020000001320000001_35020000001320000002`
- **编码格式**：国标默认 PS（Program Stream），也可协商 H.264/H.265 裸流

---

### 步骤 ⑩：ZLM 触发 on_stream_changed Hook

**源码位置**：`ZLMHttpHookListener.java:135-171`

ZLM 收到 RTP 流并解析成功后，将流注册到内部路由表，然后回调 WVP 的 Hook 接口：

```
POST http://polaris-wvp:18978/index/hook/on_stream_changed
{
    "mediaServerId": "polaris",
    "app": "rtp",
    "stream": "35020000001320000001_35020000001320000002",
    "schema": "rtsp",
    "regist": true,
    ...
}
```

WVP 收到 Hook 后发布 Spring 事件 `MediaArrivalEvent`：

```java
@PostMapping("/on_stream_changed")
public HookResult onStreamChanged(@RequestBody OnStreamChangedHookParam param) {
    MediaServer mediaServer = mediaServerService.getOne(param.getMediaServerId());
    if (param.isRegist()) {
        // 流注册 → 发布 MediaArrivalEvent
        MediaArrivalEvent event = MediaArrivalEvent.getInstance(this, param, mediaServer, ...);
        applicationEventPublisher.publishEvent(event);
    } else {
        // 流注销 → 发布 MediaDepartureEvent
        MediaDepartureEvent event = MediaDepartureEvent.getInstance(this, param, mediaServer);
        applicationEventPublisher.publishEvent(event);
    }
    return HookResult.SUCCESS();
}
```

---

### 步骤 ⑪：PlayServiceImpl 处理流到达事件

**源码位置**：`PlayServiceImpl.java:133-172` (MediaArrivalEvent 监听) + `684-698` (onPublishHandlerForPlay)

`MediaArrivalEvent` 的处理链：

```java
@Async
@EventListener
public void onApplicationEvent(MediaArrivalEvent event) {
    // 1. 过滤非 GB28181 流（只处理 app="rtp" 的流）
    // 2. 解析流 ID：35020000001320000001_35020000001320000002
    //    → deviceId = 35020000001320000001
    //    → channelId = 35020000001320000002
}

// 在 openGbRTPServerForPlay 的 hook 回调中：
public StreamInfo onPublishHandlerForPlay(MediaServer mediaServer, MediaInfo mediaInfo,
                                           Device device, DeviceChannel channel) {
    StreamInfo streamInfo = onPublishHandler(mediaServer, mediaInfo, device, channel);
    // → 调用 mediaServerService.getStreamInfoByAppAndStream()
    //   生成所有协议的流 URL

    deviceChannelService.startPlay(channel.getId(), streamInfo.getStream());
    // 更新 InviteInfo 状态为 ok
    inviteInfo.setStatus(InviteSessionStatus.ok);
    inviteInfo.setStreamInfo(streamInfo);
    inviteStreamService.updateInviteInfo(inviteInfo);
    return streamInfo;
}
```

**URL 生成逻辑**（`ZLMMediaNodeServerService.java:620-691`）：

```java
public StreamInfo getStreamInfoByAppAndStream(MediaServer mediaServer, String app,
                                               String stream, MediaInfo mediaInfo,
                                               String addr, String callId, boolean isPlay) {
    // addr = mediaServer.getStreamIp() = "192.168.0.40"

    // 生成所有协议的流地址：
    streamInfo.setRtmp(addr, mediaServer.getRtmpPort(), ...);
    //   → rtmp://192.168.0.40:10001/rtp/350200...00002

    streamInfo.setRtsp(addr, mediaServer.getRtspPort(), ...);
    //   → rtsp://192.168.0.40:10002/rtp/350200...00002

    String flvFile = String.format("%s/%s.live.flv", app, stream);
    streamInfo.setFlv(addr, mediaServer.getHttpPort(), flvFile);
    //   → http://192.168.0.40:80/rtp/350200...00002.live.flv
    streamInfo.setWsFlv(addr, mediaServer.getHttpPort(), flvFile);
    //   → ws://192.168.0.40:80/rtp/350200...00002.live.flv

    streamInfo.setHls(addr, mediaServer.getHttpPort(), ...);
    //   → http://192.168.0.40:80/rtp/350200...00002/hls.m3u8
    streamInfo.setWsHls(addr, mediaServer.getHttpPort(), ...);
    //   → ws://192.168.0.40:80/rtp/350200...00002/hls.m3u8

    streamInfo.setRtc(addr, mediaServer.getHttpPort(), ...);
    //   → http://192.168.0.40:80/index/api/webrtc?app=rtp&stream=...
}
```

**但这里有个关键：WVP 配置覆盖了 ZLM 的原始端口。**

在 `application-docker.yml` 中：

```yaml
media:
  stream-ip: ${Stream_IP}        # 192.168.0.40
  http-port: 80                   # ZLM 内部 HTTP 端口
  flv-port: ${MediaHttp:}         # 8080 → 覆盖了 httpPort，用于生成 HTTP-FLV URL
  ws-flv-port: ${MediaHttp:}      # 8080 → 覆盖了 httpPort，用于生成 WS-FLV URL
  rtmp-port: ${MediaRtmp:}        # 10001
  rtsp-port: ${MediaRtsp:}        # 10002
```

等等——实际生成 URL 时使用的是 `mediaServer.getHttpPort()`，而不是单独的 `flvPort`。这意味着 WVP 在自动配置 ZLM 时，会将 ZLM 的 HTTP 端口设为 `80`。

但在 URL 生成代码中，`setFlv` 和 `setWsFlv` 都使用 `mediaServer.getHttpPort()`（即 `80`），而不是单独的 `flvPort`。

**关键发现**：查看 `MediaConfig.java` 中的配置映射：

- `media.http-port` = `80` → `MediaServer.httpPort`
- `flv-port` 和 `ws-flv-port` 的配置实际上被 ZLM 自动配置（`auto-config: true`）时同步到 ZLM。

因此在当前 Docker 配置下，WVP 生成的实际流 URL 为：

| 协议 | URL | 端口 |
|------|-----|------|
| HTTP-FLV | `http://192.168.0.40:80/rtp/...live.flv` | 80 |
| WS-FLV | `ws://192.168.0.40:80/rtp/...live.flv` | 80 |
| RTMP | `rtmp://192.168.0.40:10001/rtp/...` | 10001 |
| RTSP | `rtsp://192.168.0.40:10002/rtp/...` | 10002 |
| WebRTC | `http://192.168.0.40:80/index/api/webrtc?...` | 80 |
| HLS | `http://192.168.0.40:80/rtp/.../hls.m3u8` | 80 |

**注意**：这些 URL 中的端口 `80` 不是浏览器可以直接访问的端口（ZLM 的 80 端口在 Docker 内部，不对外暴露）。实际播放时需要依赖 Nginx 代理或者 Nginx 的 `sub_filter` URL 重写。

但是，`PlayController` 中有 `useSourceIpAsStreamIp` 逻辑和 Nginx 的 `sub_filter` 机制，会根据配置决定最终的 URL。

---

### 步骤 ⑫：返回 StreamContent 给前端

**源码位置**：`PlayController.java:110-141` + `StreamContent.java:117-204`

`StreamContent` 将 `StreamInfo`（包含 `StreamURL` 对象）转为纯字符串 URL 的 JSON：

```java
// StreamContent 构造函数
public StreamContent(StreamInfo streamInfo) {
    this.ws_flv = streamInfo.getWs_flv().getUrl();
    // → "ws://192.168.0.40:80/rtp/350200...00002.live.flv"
    this.rtc = streamInfo.getRtc().getUrl();
    // → "http://192.168.0.40:80/index/api/webrtc?app=rtp&stream=..."
    this.rtmp = streamInfo.getRtmp().getUrl();
    // → "rtmp://192.168.0.40:10001/rtp/350200...00002"
    // ... 所有协议
}
```

最终 HTTP 响应（经过 Nginx 的 `sub_filter` 处理后）：

```json
{
    "code": 0,
    "msg": "success",
    "data": {
        "app": "rtp",
        "stream": "35020000001320000001_35020000001320000002",
        "flv": "http://192.168.0.40:80/rtp/...live.flv",
        "ws_flv": "ws://192.168.0.40:80/rtp/...live.flv",
        "rtmp": "rtmp://192.168.0.40:10001/rtp/...",
        "rtsp": "rtsp://192.168.0.40:10002/rtp/...",
        "rtc": "http://192.168.0.40:80/index/api/webrtc?app=rtp&stream=...",
        "hls": "http://192.168.0.40:80/rtp/.../hls.m3u8",
        ...
        "mediaServerId": "polaris"
    }
}
```

---

### 步骤 ⑬：浏览器接收响应并选择播放器

**源码位置**：`web/src/views/dialog/devicePlayer.vue:349-359`

前端播放器选择逻辑：

```javascript
data() {
    return {
        activePlayer: 'jessibuca',  // 默认播放器
        player: {
            jessibuca: ['ws_flv', 'wss_flv'],   // HTTP → ws_flv, HTTPS → wss_flv
            webRTC:   ['rtc', 'rtcs'],            // HTTP → rtc, HTTPS → rtcs
            h265web:  ['ws_flv', 'wss_flv']
        }
    }
}
```

**`getUrlByStreamInfo()`** 方法根据当前协议选择 URL：

```javascript
getUrlByStreamInfo() {
    if (location.protocol === 'https:') {
        videoUrl = streamInfo[this.player[this.activePlayer][1]]  // wss_flv / rtcs
    } else {
        videoUrl = streamInfo[this.player[this.activePlayer][0]]  // ws_flv / rtc
    }
    return videoUrl
}
```

默认播放器 `jessibuca` 使用的 URL 是 `streamInfo.ws_flv`，即 `ws://192.168.0.40:80/rtp/...live.flv`。

---

### 步骤 ⑭-⑮：浏览器通过 WebSocket 拉取视频流

**浏览器发起 WebSocket 连接**：

```
ws://192.168.0.40:80/rtp/35020000001320000001_35020000001320000002.live.flv
```

**问题**：这个 URL 指向端口 `80`，但 ZLM 的 80 端口不对外暴露。

这里存在一个**潜在的可达性问题**：

- 如果浏览器能直接访问到 `192.168.0.40:80`（即 Docker 的端口映射或网络路由），则 WebSocket 连接直接到达 ZLM。
- 如果浏览器无法访问端口 80（因为 Docker 未暴露），则需要通过 Nginx 代理。

根据当前配置，docker-compose.yml 中 `polaris-media` **没有**映射端口 80 到主机。因此浏览器无法直接连接 `192.168.0.40:80`。

**实际生效的播放路径**取决于 Nginx 的 `sub_filter` 或 `useSourceIpAsStreamIp` 配置：

**方案 A：Nginx `sub_filter` 重写（当前配置）**

Nginx 的 `/api/` location 中配置了 `sub_filter`，会将 API 响应中的媒体 URL 进行替换。但当前 `sub_filter` 只替换 `downloadFile` 和 `mp4_record` 相关的 URL，**不替换** `/rtp/` 路径的流地址。

因此，在当前配置下，`ws_flv` URL 中的端口 `80` 不会被 `sub_filter` 修改。

**方案 B：`useSourceIpAsStreamIp`（需手动启用）**

`PlayController.java:117-127`：

```java
if (userSetting.getUseSourceIpAsStreamIp()) {
    streamInfo = streamInfo.clone();
    String host;
    try {
        URL url = new URL(request.getRequestURL().toString());
        host = url.getHost();  // 会得到 Nginx 的地址
    } catch (MalformedURLException e) {
        host = request.getLocalAddr();
    }
    streamInfo.changeStreamIp(host);  // 将所有 URL 的 IP 替换为 Nginx IP
}
```

如果启用此选项（`user-settings.use-source-ip-as-stream-ip=true`），所有流 URL 的 host 会被替换为浏览器请求的 Nginx 地址。但**端口不会改变**（仍然是 80），因此 WebSocket 连接仍然指向端口 80。

**方案 C：直接映射 ZLM 端口 80**

在 docker-compose.yml 中添加 ZLM 的 80 端口映射：

```yaml
polaris-media:
    ports:
        - "8080:80"    # 将 ZLM HTTP 映射到主机 8080（与 Nginx 冲突，不可行）
        - "80:80"      # 或直接映射 80
```

**当前配置的实际可行路径**：由于 Nginx 配置了 `location ^~ /rtp/` 代理到 `polaris-media:80`，如果流 URL 被改为指向 Nginx（端口 8080），则可以通过 Nginx 代理访问。这需要 WVP 将 `ws-flv-port` 配置为 `8080`（Nginx 端口）而不是 `80`（ZLM 内部端口）。

---

## 三、当前配置下的实际播放路径分析

### 3.1 各种协议的可达性

| 协议 | URL 端口 | 主机是否暴露 | Nginx 代理 | 是否可达 |
|------|---------|-------------|-----------|---------|
| WS-FLV | 80 | 否 | `/rtp/` → ZLM:80 | 需要URL指向Nginx:8080 |
| HTTP-FLV | 80 | 否 | `/rtp/` → ZLM:80 | 需要URL指向Nginx:8080 |
| WebRTC | 80 | 否 | 无代理 | 不可达 |
| RTMP | 10001 | 是 | 无代理 | 可达 |
| RTSP | 10002 | 是 | 无代理 | 可达（需播放器支持） |
| HLS | 80 | 否 | `/rtp/` → ZLM:80 | 需要URL指向Nginx:8080 |

### 3.2 正常工作的路径（需要 URL 指向 Nginx）

如果 WVP 配置的 `ws-flv-port` / `flv-port` / `http-port` 等于 `8080`（Nginx 端口），则生成的 URL 为：

```
ws://192.168.0.40:8080/rtp/35020000001320000001_35020000001320000002.live.flv
```

浏览器连接 `192.168.0.40:8080` → Nginx → 匹配 `location ^~ /rtp/` → 代理到 `polaris-media:80` → ZLM → 视频画面。

**这是设计意图中的播放路径。**

### 3.3 WebRTC 不可用的原因

WebRTC 需要：
1. HTTP 信令通道（`/index/api/webrtc`）用于 SDP 交换
2. **UDP 媒体传输通道**用于视频数据

即使信令可以通过 Nginx 代理，WebRTC 的 UDP 媒体流无法通过 HTTP 反向代理转发。此外，ZLM 的 WebRTC 端口（`8000`）未在 Docker 中对外暴露。因此 WebRTC 在当前部署架构下**无法使用**。

---

## 四、部署架构对比：官方推荐 vs 本项目实现

### 4.1 官方推荐部署方式

**来源**：`doc/_content/introduction/deployment.md`

WVP-GB28181-Pro 官方文档提供了 **7 条部署指南**，其中与网络架构直接相关的核心建议为：

> "zlm使用docker部署的情况，请使用host模式，或者端口映射一致，比如映射5060，应将外部端口也映射为5060端口"

官方明确推荐的两种 Docker 部署方式：

#### 方案 A：Host 网络模式（官方首选）

```yaml
# docker-compose.yml
services:
  wvp:
    network_mode: host
  zlm:
    network_mode: host
```

所有容器直接使用宿主机网络栈，无需端口映射，容器内外端口天然一致。

#### 方案 B：Bridge + 端口映射一致（官方备选）

```yaml
# docker-compose.yml
services:
  zlm:
    ports:
      - "80:80"        # 内外端口一致
      - "10001:10001"  # 内外端口一致
      - "10002:10002"  # 内外端口一致
      - "10003:10003"  # 内外端口一致
```

使用 bridge 网络但确保**容器内端口 = 宿主机映射端口**，WVP 配置中的端口与外部可访问端口完全对应。

#### 官方其他关键建议

| 建议 | 说明 |
|------|------|
| WVP 和 ZLM 不应跨网段部署 | 两者之间有高频 REST API 和 Hook 通信，延迟敏感 |
| 测试环境关闭防火墙 | 避免网络问题干扰排查 |
| 生产环境修改默认端口 | 特别是 SIP 5060 端口容易被攻击 |
| ZLM 的 `http-port` 必须对外可达 | 浏览器需要直接访问 ZLM 的 HTTP 端口获取流 |

### 4.2 本项目的实际部署方式

本项目（`docker/docker-compose.yml`）采用的是**第三种方案**——bridge 网络 + Nginx 反向代理：

```
浏览器 ──:8080──→ Nginx ──bridge内网──→ WVP (:18978)
                                         │
                      Nginx ──bridge内网──→ ZLM (:80)
```

核心设计意图：

- **Nginx 作为唯一外部入口**，所有外部流量（API + 媒体流）都通过 Nginx 代理
- **ZLM HTTP 端口（80）不对外暴露**，仅在 Docker 内网可达
- **WVP 的 `stream-ip` + `http-port` 组合生成的流 URL，需指向 Nginx 地址**（而非 ZLM 直连地址）

### 4.3 三种方案深入对比

#### 网络架构对比

```
方案 A：Host 模式（官方首选）
┌─────────────────────────────────────────────────┐
│  宿主机网络栈 (192.168.0.40)                      │
│                                                   │
│  WVP :18978    ZLM :80  :10001  :10002  :10003   │
│  SIP :8160     WebRTC :8000                       │
│                                                   │
│  浏览器 ──直接访问──→ 任一端口                      │
│  设备   ──直接推流──→ :10003                       │
│  ZLM   ──直接回调──→ WVP :18978                   │
└─────────────────────────────────────────────────┘

方案 B：Bridge + 端口一致（官方备选）
┌─────────────────────────────────────────────────┐
│  Docker Bridge: media-net                         │
│  ┌─────┐  ┌─────┐  ┌──────┐                     │
│  │ WVP  │  │ ZLM  │  │ Redis │ ...               │
│  │:18978│  │ :80  │  │:6379  │                    │
│  └──┬───┘  └──┬───┘  └──────┘                     │
│     │         │                                  │
│     ↓         ↓  端口映射一致                       │
│  :18978    :80 :10001 :10002 :10003               │
│                                                   │
│  浏览器 ──直接访问──→ 任一映射端口                    │
│  设备   ──直接推流──→ :10003                       │
│  ZLM   ──内网回调──→ WVP :18978                    │
└─────────────────────────────────────────────────┘

方案 C：Bridge + Nginx 代理（本项目）
┌─────────────────────────────────────────────────┐
│  Docker Bridge: media-net                         │
│  ┌───────┐  ┌─────┐  ┌─────┐  ┌──────┐         │
│  │ Nginx  │  │ WVP  │  │ ZLM  │  │ Redis │ ...   │
│  │ :8080  │  │:18978│  │ :80  │  │:6379  │        │
│  └───┬───┘  └──┬──┘  └──┬──┘  └──────┘          │
│      │         │        │                         │
│      ↓         ↓        ↓ 不对外暴露               │
│   :8080    :18978   (不暴露) :10001 :10002 :10003  │
│                                                   │
│  浏览器 ──:8080──→ Nginx ──代理──→ WVP/ZLM        │
│  设备   ──直接推流──→ :10003                       │
│  ZLM   ──内网回调──→ WVP :18978                    │
└─────────────────────────────────────────────────┘
```

#### 功能对比矩阵

| 维度 | A: Host 模式 | B: Bridge + 端口一致 | C: Bridge + Nginx (本项目) |
|------|-------------|--------------------|-----------------------------|
| **网络复杂度** | 最低 | 中等 | 最高 |
| **端口冲突风险** | 高（所有服务共享宿主机端口） | 中（需手动管理端口映射） | 低（Nginx 统一对外，仅占 8080） |
| **WS-FLV 播放** | 直接可达 | 直接可达 | 需要 URL 指向 Nginx，经代理转发 |
| **WebRTC 播放** | 可用（UDP 直通） | 可用（需暴露 8000 端口） | 不可用（UDP 无法通过 HTTP 代理） |
| **RTMP 播放** | 直接可达 | 直接可达 | 直接可达（端口已暴露） |
| **RTSP 播放** | 直接可达 | 直接可达 | 直接可达（端口已暴露） |
| **HLS 播放** | 直接可达 | 直接可达 | 需要 URL 指向 Nginx，经代理转发 |
| **流 URL 正确性** | 天然正确（内外端口一致） | 天然正确（内外端口一致） | 需要额外处理（WVP 端口配置 ≠ ZLM 实际端口） |
| **SIP 信令** | 直接可达 | 直接可达 | 直接可达（端口已暴露） |
| **RTP 收流** | 直接可达 | 直接可达 | 直接可达（端口已暴露） |
| **安全性** | 低（所有端口对外） | 中（按需暴露） | 高（ZLM 不直接暴露） |
| **Nginx sub_filter** | 不需要 | 不需要 | 需要（用于重写 API 响应中的下载 URL） |
| **多实例扩展** | 困难（端口冲突） | 可行 | 可行 |
| **官方支持度** | 首选推荐 | 备选推荐 | 非官方方案，需自行处理兼容性 |

#### 流 URL 生成差异

这是三种方案最核心的差异点——WVP 生成的流 URL 是否能被浏览器直接访问。

**方案 A（Host 模式）**：

WVP 配置 `media.http-port=80`，`media.stream-ip=192.168.0.40`。

```
ws_flv = ws://192.168.0.40:80/rtp/xxx.live.flv    ← 浏览器可直接访问
rtc    = http://192.168.0.40:80/index/api/webrtc   ← 浏览器可直接访问
rtmp   = rtmp://192.168.0.40:10001/rtp/xxx         ← 播放器可直接访问
```

URL 天然正确，因为容器内外端口完全一致。

**方案 B（Bridge + 端口一致）**：

WVP 配置同上，但 Docker 做了端口映射 `80:80`。

```
ws_flv = ws://192.168.0.40:80/rtp/xxx.live.flv    ← 浏览器访问 :80，Docker 映射到容器 :80，正确
```

URL 同样天然正确，因为映射端口 = 容器端口。

**方案 C（本项目：Bridge + Nginx）**：

WVP 配置 `media.http-port=80`，但 ZLM 的 80 端口不对外暴露。

```
ws_flv = ws://192.168.0.40:80/rtp/xxx.live.flv    ← 浏览器无法访问 :80（未暴露）
```

需要额外处理才能使流 URL 可达：

| 处理方式 | 原理 | 当前是否启用 |
|---------|------|------------|
| 将 `media.http-port` 改为 `8080` | WVP 生成 URL 时使用 8080 端口，指向 Nginx | **未启用**（当前 `http-port=80`） |
| Nginx `sub_filter` 重写 | Nginx 在 API 响应中将 `:80` 替换为 `:8080` | **未启用**（当前只替换 downloadFile/mp4_record） |
| `useSourceIpAsStreamIp` | 替换 URL 中的 IP，但不替换端口 | **未启用**（且不解决端口问题） |

**结论**：方案 C 的设计意图是让流 URL 指向 Nginx（`ws://IP:8080/rtp/...`），由 Nginx 代理到 ZLM。但当前配置中 `media.http-port=80` 导致 WVP 生成的 URL 端口是 `80` 而非 `8080`，存在**端口不匹配**问题。

#### RTP 收流路径差异

设备的 RTP 推流在三种方案中的路径：

| 方案 | 设备推流目标 | 路径 |
|------|------------|------|
| A: Host | `192.168.0.40:10003` | 设备 → 宿主机:10003 → ZLM |
| B: Bridge | `192.168.0.40:10003` | 设备 → 宿主机:10003 → Docker 映射 → ZLM:10003 |
| C: Nginx | `192.168.0.40:10003` | 设备 → 宿主机:10003 → Docker 映射 → ZLM:10003 |

三种方案的 RTP 收流路径基本一致，因为 SIP SDP 中的 `SDP_IP` 和 RTP 端口都是直接暴露给设备的。Nginx 不参与 RTP 收流。

### 4.4 本项目选择 Nginx 代理方案的原因

尽管官方未推荐 Nginx 代理方案，本项目选择此方案可能基于以下考量：

| 考量 | 说明 |
|------|------|
| **安全性** | ZLM 不直接暴露在公网，减少攻击面 |
| **统一入口** | 所有外部流量经 Nginx，便于日志、监控、限流 |
| **多租户/多实例** | 通过 Nginx 路由可以支持多个 WVP+ZLM 实例 |
| **前端部署** | Nginx 同时托管前端 SPA，无需额外 Web 服务器 |
| **URL 重写** | `sub_filter` 可以在 API 响应中替换媒体 URL，适配不同网络环境 |

### 4.5 方案 C 的已知限制与应对

| 限制 | 影响 | 应对方式 |
|------|------|---------|
| WebRTC 不可用 | 低延迟播放场景受限 | 只能使用 WS-FLV（延迟略高） |
| 流 URL 端口需特殊处理 | 配置不当会导致播放失败 | 将 `media.http-port` 设为 `8080`（Nginx 端口），或在 Nginx 增加 `sub_filter` 规则 |
| Nginx 成为单点 | Nginx 故障则全系统不可用 | 可通过 Nginx 高可用（keepalived 等）解决 |
| WebSocket 长连接占用 Nginx | 高并发时 Nginx 资源压力大 | 调整 Nginx worker 连接数，或考虑流媒体直连 |
| `sub_filter` 仅处理 JSON | 非 JSON 响应中的 URL 不会被替换 | 当前只对 `application/json` 生效 |
| `sub_filter` 对 gzip 响应无效 | WVP 返回 gzip 压缩的 JSON 时，`sub_filter` 字符串替换静默失败，导致下载/录像 URL 不会被重写 | 在 `/api/` location 中添加 `proxy_set_header Accept-Encoding "";`，禁止上游返回压缩响应 |

**`Accept-Encoding` 问题详解**：当前 `nginx.conf.template` 的 `/api/` location 块缺少 `proxy_set_header Accept-Encoding "";`。Nginx 的 `sub_filter` 模块只能处理未压缩的明文响应体。如果浏览器请求携带 `Accept-Encoding: gzip`（所有现代浏览器默认行为），Nginx 会将此头透传给 WVP，WVP 可能返回 gzip 压缩的 JSON。此时 `sub_filter` 无法匹配响应体中的任何字符串，`downloadFile` 和 `mp4_record` 的 URL 重写会静默失败。修复方式是在 `proxy_pass` 之前添加 `proxy_set_header Accept-Encoding "";`，明确告知 WVP 不要压缩响应。

### 4.6 流 URL 端口不匹配的根因分析

上述"流 URL 端口需特殊处理"这一限制，根因不在 Nginx 配置，而在 WVP 源码中 `httpPort` 被同时用于两个完全不同的用途。

**根因**：`ZLMMediaNodeServerService.java:667-680`

```java
// 所有 HTTP 类协议的 URL 生成都使用 getHttpPort()
String flvFile = String.format("%s/%s.live.flv%s", app, stream, callIdParam);
streamInfo.setFlv(addr, mediaServer.getHttpPort(), ...);      // → 端口 80
streamInfo.setWsFlv(addr, mediaServer.getHttpPort(), ...);    // → 端口 80

String mp4File = String.format("%s/%s.live.mp4%s", app, stream, callIdParam);
streamInfo.setFmp4(addr, mediaServer.getHttpPort(), ...);     // → 端口 80
streamInfo.setWsMp4(addr, mediaServer.getHttpPort(), ...);    // → 端口 80

streamInfo.setHls(addr, mediaServer.getHttpPort(), ...);      // → 端口 80
streamInfo.setTs(addr, mediaServer.getHttpPort(), ...);       // → 端口 80
streamInfo.setRtc(addr, mediaServer.getHttpPort(), ...);      // → 端口 80
```

而 `MediaServer` 对象上实际存在独立的端口字段，且配置文件已设值：

| 配置项 | 配置值 | MediaServer 字段 | URL 生成是否使用 |
|--------|-------|-----------------|----------------|
| `media.http-port` | `80` | `httpPort` | **是**（所有 HTTP 类 URL） |
| `media.flv-port` | `8080` | `flvPort` | **否** |
| `media.ws-flv-port` | `8080` | `wsFlvPort` | **否** |

`flvPort`/`wsFlvPort` 在配置中已设为 `8080`（Nginx 端口），但 URL 生成代码完全忽略了它们，统一使用 `httpPort`（`80`，ZLM 内部端口）。

**更深一层：ZLM 实现与 ABL 实现的差异**。WVP 的媒体服务层有两种实现——ZLM（本项目使用）和 ABL（Asterisk/FreeSWITCH 类方案）。搜索代码库发现：

- **ABL 实现**（`ABLMediaNodeServerService.java:225-235`）**正确使用了** `getFlvPort()` / `getWsFlvPort()`，能区分 HTTP-FLV 和 WS-FLV 的独立端口
- **ZLM 实现**（`ZLMMediaNodeServerService.java:667-680`）**完全没有读取** `flvPort` / `wsFlvPort`，所有 HTTP 类协议统一使用 `getHttpPort()`

这意味着 `application-docker.yml` 中 `flv-port: ${MediaHttp:}`（= `8080`）的配置在 ZLM 路径下**完全是死代码**。问题不是 `sub_filter` 配置遗漏，而是 ZLM 实现层面缺少"内部 API 调用端口"与"外部流播放端口"的区分能力。ABL 实现已经做到了这一点，ZLM 实现没有跟进。

**为什么不能直接改 `http-port` 为 `8080`**：`httpPort` 同时承担两个职责，改一个会破坏另一个：

| 职责 | 需要的端口 | 用途 |
|------|-----------|------|
| WVP → ZLM REST API 通信 | `80` | `polaris-media:80`（Docker 内网，ZLM 实际监听端口） |
| 返回给浏览器的流 URL | `8080` | 浏览器需通过 Nginx 代理访问（`IP:8080`） |

将 `http-port` 改为 `8080` 会导致 WVP 尝试访问 `polaris-media:8080`（不通），ZLM 的 REST API 调用全部失败。

**ZLM 自身不支持端口分离**。ZLMediaKit 的架构是单端口多路复用——`[http]` 配置节只有一个 `port` 选项，API 和所有 HTTP 类流（FLV、HLS、TS、FMP4、WebRTC 信令、静态文件下载）共享同一个端口。源码 `HttpSession.cpp` 的 `onHttpRequest_GET()` 方法在同一端口上按 URL 路径分发：

| URL 路径模式 | 功能 |
|-------------|------|
| `/index/api/*` | REST API（WVP 调用 ZLM 的接口） |
| `*.live.flv` | HTTP-FLV 实时流 |
| `*.live.ts` | HTTP-TS 实时流 |
| `*.live.mp4` | HTTP-FMP4 实时流 |
| `*.m3u8` / `*.ts` | HLS 流 |
| 其他路径 | 静态文件下载 |

`[api]` 配置节没有独立的端口选项，ZLM 无法将 API 监听和流媒体分发拆分到不同端口。因此在 Nginx 代理方案下，端口分离只能在 WVP 的 URL 生成层实现。

**可行的源码级修复方案**：

在 `MediaServer` 上增加"对外暴露端口"的概念，将"内部通信端口"与"对外 URL 端口"分离。例如增加 `externalHttpPort` 配置项：

```yaml
# application-docker.yml
media:
  http-port: 80                    # 内部：WVP → ZLM REST API
  external-http-port: 8080         # 对外：生成流 URL 时的端口（指向 Nginx）
```

然后在 `ZLMMediaNodeServerService.getStreamInfoByAppAndStream()` 中，URL 生成改用 `getExternalHttpPort()`：

```java
int urlPort = mediaServer.getExternalHttpPort() > 0
    ? mediaServer.getExternalHttpPort()
    : mediaServer.getHttpPort();

streamInfo.setFlv(addr, urlPort, ...);
streamInfo.setWsFlv(addr, urlPort, ...);
streamInfo.setHls(addr, urlPort, ...);
streamInfo.setTs(addr, urlPort, ...);
streamInfo.setFmp4(addr, urlPort, ...);
streamInfo.setRtc(addr, urlPort, ...);
```

这样一个配置项就能统一解决所有 HTTP 类协议的 URL 端口问题，不再依赖 `sub_filter` 的运行时重写。

**不建议的修复方式**：逐个协议改用 `getFlvPort()`/`getWsFlvPort()`。这只覆盖 FLV/WS-FLV 两个协议，HLS、TS、FMP4、WebRTC 的 URL 仍然指向端口 80，问题未根治。而且需要逐行修改，不符合 DRY 原则。

---

## 五、ZLM Hook 回调体系

ZLM 通过 HTTP Hook 向 WVP 报告各种事件，这是整个系统的核心通信机制之一。

### 5.1 Hook 配置

**ZLM 配置**（`docker/media/config.ini`）：

```ini
[hook]
enable=1
on_play=http://polaris-wvp:18978/index/hook/on_play
on_publish=http://polaris-wvp:18978/index/hook/on_publish
on_stream_changed=http://polaris-wvp:18978/index/hook/on_stream_changed
on_stream_none_reader=http://polaris-wvp:18978/index/hook/on_stream_none_reader
on_stream_not_found=http://polaris-wvp:18978/index/hook/on_stream_not_found
on_server_keepalive=http://polaris-wvp:18978/index/hook/on_server_keepalive
on_server_started=http://polaris-wvp:18978/index/hook/on_server_started
on_record_mp4=http://polaris-wvp:18978/index/hook/on_record_mp4
on_send_rtp_stopped=http://polaris-wvp:18978/index/hook/on_send_rtp_stopped
on_rtp_server_timeout=http://polaris-wvp:18978/index/hook/on_rtp_server_timeout
timeoutSec=30
```

### 5.2 完整 Hook 列表

| Hook | 触发时机 | WVP 处理 |
|------|---------|---------|
| `on_server_started` | ZLM 启动 | 注册 ZLM 节点，同步配置 |
| `on_server_keepalive` | 每 10 秒 | 更新 ZLM 心跳状态 |
| `on_stream_changed` | 流注册/注销 | 发布 `MediaArrivalEvent` / `MediaDepartureEvent` |
| `on_stream_none_reader` | 流无人观看 | 决定是否自动关闭流 |
| `on_stream_not_found` | 流未找到 | 触发自动点播（`auto-apply-play`） |
| `on_play` | 播放鉴权 | 验证播放请求合法性 |
| `on_publish` | 推流鉴权 | 设置流参数，返回推流配置 |
| `on_record_mp4` | MP4 录制完成 | 处理录制文件 |
| `on_send_rtp_stopped` | RTP 发送停止 | 处理级联推流停止 |
| `on_rtp_server_timeout` | RTP 收流超时 | 清理超时的 RTP Server |

---

## 六、流生命周期管理

### 6.1 流命名规则

**源码位置**：`MediaStreamUtil.java`

| 类型 | App | Stream ID 格式 | 示例 |
|------|-----|---------------|------|
| 实时点播 | `rtp` | `{deviceId}_{channelId}` | `rtp/340200...001_340200...002` |
| 录像回放 | `rtp` | `{deviceId}_{channelId}_{startTime}_{endTime}` | `rtp/340200...001_340200...002_20260425080000_20260425090000` |
| 语音广播 | `broadcast` | `{deviceId}_{channelId}` | `broadcast/340200...001_340200...002` |
| 语音对讲 | `talk` | `{deviceId}_{channelId}` | `talk/340200...001_340200...002` |

### 6.2 SSRC 管理

- SSRC 由 `SSRCFactory` 统一管理，每个 `MediaServer` 有独立的 SSRC 池。
- SSRC 为 10 位十六进制字符串（如 `0A000001`）。
- 点播前分配，点播结束或超时后释放。
- 单端口模式下 SSRC 是区分不同流的关键标识。

### 6.3 InviteInfo 状态机

```
ready → invite → ok → error/timeout
                   ↑
                   └── 流到达（onPublishHandlerForPlay）
```

| 状态 | 含义 |
|------|------|
| `ready` | RTP Server 已创建，等待设备推流 |
| `invite` | SIP INVITE 已发送 |
| `ok` | 流已到达，播放成功 |
| `error` | 点播失败 |

---

## 七、错误处理与超时机制

### 7.1 点播超时

**源码位置**：`PlayController.java:96-108`

```java
DeferredResult<WVPResult<StreamContent>> result = new DeferredResult<>(
    userSetting.getPlayTimeout().longValue()  // 默认 30000ms
);
result.onTimeout(() -> {
    // 释放 RTP Server
    // 清理 InviteInfo
    // 更新通道状态为未播放
});
```

### 7.2 SIP 事务超时

**源码位置**：`SIPCommander.java:296`

SIP INVITE 发送时设置了超时回调：

```java
sipSender.transmitRequest(..., request,
    errorEvent -> { /* SIP 传输失败 */ },
    okEvent -> { /* 收到 200 OK */ },
    timeout   /* userSetting.playTimeout */
);
```

### 7.3 RTP Server 超时

ZLM 的 RTP Server 有独立的超时机制。如果在超时时间内未收到 RTP 数据，ZLM 触发 `on_rtp_server_timeout` Hook，WVP 收到后清理资源。

---

## 八、停止点播流程

### 8.1 API 调用

```
GET /api/play/stop/{deviceId}/{channelId}
```

**源码位置**：`PlayController.java:149-168`

### 8.2 内部流程

```
playService.stop(InviteSessionType.PLAY, device, channel, streamId)
  ├── 查询 InviteInfo
  ├── cmder.streamByeCmd() → 发送 SIP BYE 给设备
  ├── receiveRtpServerService.closeRTPServer() → 关闭 ZLM 的 RTP Server
  ├── sessionManager.removeByStream() → 释放 SSRC
  ├── inviteStreamService.removeInviteInfo() → 清理 InviteInfo
  └── deviceChannelService.stopPlay() → 更新通道状态
```

### 8.3 SIP BYE 消息

```
BYE sip:35020000001320000002@192.168.1.100:5060 SIP/2.0
Via: SIP/2.0/UDP 192.168.0.40:8160;branch=z9hG4bK...
From: <sip:35020000002000000001@3502000000>;tag=...
To: <sip:35020000001320000002@3502000000>;tag=...
Call-ID: ...@192.168.0.40
CSeq: 2 BYE
Content-Length: 0
```

设备收到 BYE 后停止推流，ZLM 检测到流离开，触发 `on_stream_changed(regist=false)` Hook。

---

## 九、自动点播机制

### 9.1 按需拉流（Stream-on-Demand）

**配置**：`user-settings.stream-on-demand: true`

当播放器请求一个不存在的流时：

1. 播放器 → Nginx → ZLM 请求流 `/rtp/xxx`
2. ZLM 发现流不存在 → 触发 `on_stream_not_found` Hook
3. WVP 收到 Hook → `MediaNotFoundEvent`
4. `PlayServiceImpl` 解析 streamId 得到 deviceId 和 channelId
5. 自动发起点播流程（与手动点击相同）
6. 流到达后自动返回给播放器

**源码位置**：`PlayServiceImpl.java:248-286`

```java
@EventListener
public void onApplicationEvent(MediaNotFoundEvent event) {
    String[] s = event.getStream().split("_");
    if (s.length == 2) {
        // 实时视频自动点播
        play(event.getMediaServer(), deviceId, channelId, null, callback);
    } else if (s.length == 4) {
        // 录像回放自动点播
        playBack(event.getMediaServer(), device, deviceChannel, startTime, endTime, callback);
    }
}
```

---

## 十、核心源码文件索引

| 文件 | 作用 |
|------|------|
| `web/src/api/play.js` | 前端点播 API 调用 |
| `web/src/views/dialog/devicePlayer.vue` | 前端播放器组件（播放器选择、URL 获取） |
| `gb28181/controller/PlayController.java` | 点播 REST API 入口 |
| `gb28181/service/impl/PlayServiceImpl.java` | 点播核心编排（1857 行） |
| `gb28181/transmit/cmd/impl/SIPCommander.java` | SIP 命令构造与发送 |
| `gb28181/transmit/event/response/impl/InviteResponseProcessor.java` | SIP INVITE 200 OK 处理 |
| `media/zlm/ZLMHttpHookListener.java` | ZLM Hook 接收与事件分发 |
| `media/zlm/ZLMMediaNodeServerService.java` | ZLM 节点管理、流 URL 生成 |
| `common/StreamInfo.java` | 流信息数据模型 |
| `common/StreamURL.java` | 流 URL 数据模型（`protocol://host:port/file`） |
| `vmanager/bean/StreamContent.java` | API 响应 DTO（StreamInfo → 纯字符串 URL） |
| `common/enums/MediaStreamUtil.java` | 流命名规则常量 |
| `conf/MediaConfig.java` | 媒体配置读取 |
| `gb28181/session/SipInviteSessionManager.java` | SIP INVITE 会话管理 |
| `gb28181/session/SSRCFactory.java` | SSRC 分配工厂 |

---

## 十一、数据流总结

```
┌─────────────────────────────────────────────────────────────────────────┐
│                          点播数据流全景图                                  │
│                                                                         │
│  ┌──────┐  HTTP GET   ┌──────┐  proxy_pass  ┌─────┐                    │
│  │ 浏览器 │ ─────────→ │ Nginx │ ──────────→ │ WVP  │                    │
│  │      │ ←───────── │      │ ←────────── │     │                    │
│  └──────┘  JSON resp  └──────┘  StreamContent └─────┘                    │
│     │                                              │                     │
│     │                    ┌────── SIP INVITE ───────┤                     │
│     │                    │                         │                     │
│     │                    ▼                         ▼                     │
│     │              ┌──────────┐            ┌──────────────┐             │
│     │              │ 设备/IPC  │            │ ZLM 流媒体    │             │
│     │              │ (GB终端)  │            │ (polaris-media)│            │
│     │              └──────────┘            └──────────────┘             │
│     │                    │                         ▲                     │
│     │                    │   RTP 媒体流              │                     │
│     │                    └─────────────────────────┘                     │
│     │                                                              │
│     │    WS 连接            代理 WS              ZLM 返回视频帧          │
│     │  ws://IP:8080/rtp/..  ──────→  polaris-media:80  ──────→         │
│     │                                                              │
│     │  ◄═══════════════════  FLV 视频数据  ═════════════════════      │
│     │                                                              │
└─────────────────────────────────────────────────────────────────────────┘
```
