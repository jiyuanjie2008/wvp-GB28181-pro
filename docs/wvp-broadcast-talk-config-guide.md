# WVP 语音喊话 / 语音对讲 配置指南

> 来源：整理自 WVP-PRO 官方文档 `doc/_content/ability/continuous_broadcast.md`、上游对讲实现参考（gitee/yuxiuliang/wvp-gb28181-talk）、GB28181 SIP 抓包实例，并结合本项目 Docker 部署实测。
> 适用版本：WVP-PRO 2.7.4
> 维护：本部署（Docker：polaris-wvp / polaris-media / polaris-redis / polaris-mysql / polaris-nginx）

---

## 一、两种模式的本质

GB28181-2016 中语音对讲分两种模式，**WVP 实际只用同一套逻辑处理**：

| 模式 | 方向 | 说明 |
|------|------|------|
| **broadcast（广播 / 喊话）** | 服务端 → 设备，**单向** | WVP 实现的就是这种；要"双向"需另叠加一路点播视频 |
| **talk（对讲）** | 双向 | 在 GB28181-**2022** 中已移除，WVP 不再单独处理，复用 broadcast 逻辑 |

> **最关键的差异（与点播相反）：喊话的 INVITE 由「设备」主动发给 WVP。** 因此收发语音流用 UDP / TCP 被动 / TCP 主动 哪种方式，**由设备决定**，WVP 无法强选。这直接决定了设备能否用于公网喊话。

### broadcast 信令流程（官方时序）

```
WVP ──语音广播通知(MESSAGE/Broadcast)──> 设备
WVP <────────── 200 OK ──────────────── 设备
WVP <── 语音广播应答(MESSAGE/Response)── 设备
WVP ─────────── 200 OK ───────────────> 设备
WVP <────────── INVITE(SDP) ─────────── 设备     ← INVITE 由设备发起，SDP 含设备收流端口
WVP ─────── 200 OK(携带 SDP) ──────────> 设备
WVP <────────── ACK ─────────────────── 设备
ZLM ────── 向设备发送语音流(RTP) ──────> 设备
```

设备发起 INVITE 时的 SDP 关键行：

```
m=audio 15062 RTP/AVP 8        # RTP/AVP=UDP（仅局域网）；TCP/RTP/AVP 才能公网
a=recvonly                      # 设备只收不发（音频方向：ZLM→设备）
a=rtpmap:8 PCMA/8000            # G.711a，8kHz
y=0200000017                    # SSRC
f=v/a/1/8/1                     # 音频编码描述
```

---

## 二、使用条件与限制（兼容性结论）

**能不能公网喊话，取决于设备收流模式：**

| 厂商 / 设备 | 收流模式 | 公网喊话 | 备注 |
|------------|---------|---------|------|
| 海康（多数旧型号） | 仅 UDP | ❌ 不可 | UDP 无法跨 NAT；WVP 在公网时连不到设备 SDP 里的私网地址 |
| 海康（新版 / 升级后） | TCP 主动 | ✅ 可 | 可向厂家要升级包，升级后设备主动 TCP 连平台 |
| 大华 / 多数执法记录仪 | TCP 主动 | ✅ 可 | 推荐；另有 Dahua 私有 TALK 模式（INVITE 由平台发起，可指定 TCP） |
| 大华（部分） | 可改 | ✅ 可 | 将接入模块「识别码」首位改为 `4`，可切到标准国标 TCP 对讲 |

> 结论：**公网喊话优先选大华或支持 TCP 主动的设备；海康旧设备只能局域网用。** 这是设备硬件/固件决定的，平台侧无法绕过。

---

## 三、生产环境必要条件

### 1. 必须 HTTPS（浏览器安全机制，否则调不起麦克风）

- **公网**：直接用 CA / 云厂商证书。
- **局域网**：用 [mkcert](https://github.com/FiloSottile/mkcert/releases/tag/v1.4.4) 生成自签名证书，**WVP 和 ZLM 都要配**。
  ```bash
  ./mkcert-v1.4.4-linux-amd64 -install
  ./mkcert-v1.4.4-linux-amd64 局域网IP 局域网IP2 局域网IP3
  # 得到 *-key.pem 和 *.pem，配置到 WVP
  cat *.pem *-key.pem > ./zlm.pem    # 合成 ZLM 用的证书
  ```
  ZLM 用证书两种方式：① 删除并替换 `config.ini` 指向的 `default.pem`；② 启动加 `-s zlm.pem`。

> **自签名证书必须在浏览器里同时信任 WVP 站点和 ZLM 站点**，否则 WebRTC 推流（麦克风→ZLM）起不来。

### 2. 通道的「音频」选项必须打开

设备上线默认打开。**未满足 HTTPS + 音频开启两个条件时，前端喊话按钮不可点击**（悬浮有提示）。

### 3. ZLM 必须启用 WebRTC

ZLM 编译时需 `-DENABLE_WEBRTC=true`，否则 WebRTC 推流不可用。官方镜像 `zlmediakit/zlmediakit:master` 默认已启用。

---

## 四、快速功能验证（ffmpeg，绕开 HTTPS / 浏览器）

测试阶段不必折腾浏览器证书，用 ffmpeg 模拟推流，能听到设备播放即代表链路通：

```bash
ffmpeg -re -i test.mp3 -acodec pcm_alaw -ar 8000 -ac 1 -f rtsp \
  'rtsp://{zlm的IP}:{zlm的RTSP端口}/broadcast/{设备国标编号}_{通道国标编号}?sign={md5(pushKey)}'
```

示例：

```bash
ffmpeg -re -i test.mp3 -acodec pcm_alaw -ar 8000 -ac 1 -f rtsp \
  'rtsp://192.168.1.3:22554/broadcast/34020000001320000001_34020000001320000001?sign=41db35390ddad33f83944f44b8b75ded'
```

> `sign = md5(pushKey)`，对应 `/api/user/userInfo` 返回的 `pushKey`。本部署关闭了接口鉴权，已对 `UserController.getUserInfo()` 做了 fallback（见 `case-2026-06-29`），否则此处拿不到 pushKey。

---

## 五、本部署（Docker）配置要点

### 1. 三层端口必须一致

| 端口范围 | 协议 | 用途 | 配置位置 |
|---------|------|------|---------|
| `6080` | TCP | ZLM HTTP API + WS-FLV 播流 | `docker-compose.yml` |
| `31000-31500` | TCP/UDP | 终端推视频到 ZLM（收流） | `docker-compose.yml` + `application-docker.yml` |
| `10003` | TCP/UDP | 单端口模式收流 | `config.ini` |
| **`50502-50506`** | **TCP** | **ZLM 发音频给终端（发流，TCP 被动）** | **三层都要，最易漏** |
| `18000` | UDP/TCP | WebRTC 音频推流 | `docker-compose.yml` + `config.ini` |

- WVP 配置：`application-docker.yml` → `media.rtp.send-port-range: 50502,50506`
- ZLM 配置：`docker/media/config.ini` 的 `[rtp]` 端口段
- Docker 映射：`docker-compose.yml` 的 `polaris-media.ports` 必须含 `50502-50506:50502-50506/tcp`

> **发流端口（ZLM→设备）最容易被遗漏。** 详见 `case-2026-06-30-send-port-missing-broadcast-no-audio.md`：信令全通但终端无声，根因就是 `send-port-range` 没在 compose 映射。

### 2. pushKey / 推流签名链路

喊话前端两阶段：
1. `POST /api/play/broadcast/{deviceId}/{channelId}` → 拿 WebRTC 推流地址（`data.streamInfo.rtc`）
2. `POST /api/user/userInfo` → 拿 `pushKey`，生成 `sign=md5(pushKey)` 拼到推流 URL

> 关闭接口鉴权时第 2 步会失败，已修复，见 `case-2026-06-29-broadcast-fails-when-interface-auth-disabled.md`。

### 3. 重建容器后务必校验端口映射

```bash
docker port docker-polaris-media-1        # 应同时含 6080、50502-50506、31000-31500、18000
docker compose up -d polaris-media        # 重建（注意端口冲突会导致映射丢失，需 rm 后重建）
```

---

## 六、排障决策树

```
点击喊话/对讲
 ├─ 前端按钮不可点击？
 │    ├─ 是 → 检查：①站点是否 HTTPS  ②通道「音频」选项是否开启
 │    └─ 否 ↓
 ├─ F12 看 /api/play/broadcast 返回 code != 0？
 │    ├─ 是 → 看 msg；多半是 pushKey/鉴权（case-2026-06-29）
 │    └─ 否 ↓
 ├─ F12 看 /api/user/userInfo 返回 code != 0？
 │    ├─ 是 → 鉴权关闭时缺 fallback（case-2026-06-29）
 │    └─ 否 ↓（WebRTC 推流应已建立）
 ├─ WVP 日志：收到设备 INVITE？
 │    ├─ 否 → 设备未发 INVITE：检查设备是否支持喊话、收流模式（UDP 设备公网不可用）
 │    ├─ 报 "无法从请求中获取到来源id，返回400错误"
 │    │      → decode() 解析失败：Subject/From 头格式异常（设备品牌兼容问题，见 issue #2077）
 │    └─ 是 ↓
 ├─ WVP 日志：发了 ACK，但终端无声？
 │    ├─ ZLM 日志 "接受rtp/rtcp/datachannel超时"（TCP 被动）
 │    │      → 发流端口未映射（case-2026-06-30，检查 50502-50506）
 │    ├─ ZLM 主动发流但设备收不到
 │    │      → addressStr 错误（NAT 地址解析，见 InviteRequestProcessor L548）
 │    └─ 设备播放了但音质/延迟问题
 │         → 单向 broadcast 延迟 5-6s 属正常（issue #1955 讨论优化）
```

---

## 七、ffmpeg 测试流程（官方时序）

```
FFMPEG ──推流──> ZLMediaKit
WVP    <──通知收到喊话推流(携带设备/通道)── ZLMediaKit
WVP    ──开始语音对讲──> 设备
WVP    <──语音对讲建立成功(携带收流端口)── 设备
WVP    ──通知 ZLM 把流推到设备收流端口──> ZLMediaKit
ZLM    ──向设备推流──> 设备
```

> 此过程推流即可，无需调用任何额外接口。听到设备播放推送的音频 = 成功。

---

## 八、参考来源

- 官方文档站：https://doc.wvp-pro.cn/
- 官方语音对讲文档（GitHub raw）：`doc/_content/ability/continuous_broadcast.md`
- 上游对讲实现参考（含大华 TALK 信令）：https://gitee.com/yuxiuliang/wvp-gb28181-talk
- 完整 SIP 抓包实例：https://www.cnblogs.com/feixiang-energy/p/16776241.html
- Issue #44（语音对讲原始讨论）：https://github.com/648540858/wvp-GB28181-pro/issues/44
- Issue #2077（喊话无声 + InviteRequestProcessor 400）：https://github.com/648540858/wvp-GB28181-pro/issues/2077
- Issue #1955（单向喊话延迟优化）：https://github.com/648540858/wvp-GB28181-pro/issues/1955
- ZLMediaKit #2217 / #2201（WVP+ZLM 对讲/广播）：https://github.com/ZLMediaKit/ZLMediaKit/issues/2217

---

## 九、本项目相关案例索引

| 案例 | 现象 | 根因 |
|------|------|------|
| `case-2026-06-29-broadcast-fails-when-interface-auth-disabled.md` | 关闭接口鉴权后喊话失败 | `/api/user/userInfo` 拿不到 pushKey |
| `case-2026-06-30-send-port-missing-broadcast-no-audio.md` | 信令通但终端无声 | `send-port-range` 未在 compose 映射 |
| `case-2026-06-23-video-pull-vs-talkback-webrtc-websocket.md` | 点播 vs 对讲 WebRTC/WebSocket 区分 | 媒体通道与推流地址混淆 |
