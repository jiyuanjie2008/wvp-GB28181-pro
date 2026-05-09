# WVP 国标设备视频流播放失败 — 根因分析（第三版修订）

## 问题描述

Docker 部署环境下，国标设备（摄像头）通过 SIP INVITE 成功收流，但浏览器播放视频失败。

**现象：** WVP API `/api/play/start/` 返回 200 OK，SIP 信令正常，RTP 收流正常，ZLM 流注册成功。但浏览器尝试播放时连接超时，约 20 秒后 ZLM 因无观众自动关闭流。

---

## 根因结论

**WVP 官方项目本身就支持 nginx 反向代理流媒体，我们的部署完全遵循了官方 Docker 模式。问题出在官方 nginx 配置模板中的 `sub_filter` URL 重写规则不完整——只覆盖了文件下载 URL，未覆盖流播放 URL。**

### 证据链

**1. 官方仓库 `docker-compose.yml` 注释掉了 ZLM HTTP 端口映射，明确说明走 nginx 代理：**

```yaml
#- "6080:80/tcp"     # [播流]HTTP  安全考虑-非测试阶段需要注释掉，改为由nginx代理播流地址
```

**2. 官方 `nginx.conf.template` 提供了完整的流代理基础设施：**

```nginx
# ✅ 有 WebSocket 支持的流代理
location ^~ /rtp/ {
    proxy_pass http://polaris-media:80;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
}
```

**3. 但 `sub_filter` URL 重写规则不完整：**

```nginx
# ✅ 已覆盖：文件下载 URL
sub_filter "http://$original_host/index/api/downloadFile" "mediaserver/api/downloadFile";
sub_filter "http://$original_host:80/index/api/downloadFile" "mediaserver/api/downloadFile";

# ✅ 已覆盖：录像回放 URL
sub_filter "http://$original_host/mp4_record" "mp4_record";
sub_filter "http://$original_host:80/mp4_record" "mp4_record";

# ❌ 未覆盖：流播放 URL（以下规则不存在）
# sub_filter "ws://$original_host:80/rtp/" "ws://$original_host:8080/rtp/";
# sub_filter "http://$original_host:80/index/api/webrtc" "http://$original_host:8080/index/api/webrtc";
```

**结论：** nginx 的 `/rtp/` 代理和 WebSocket 支持已就绪，浏览器能通过 `ws://host:8080/rtp/...` 访问流。但 WVP 返回的流 URL 指向 `ws://host:80/rtp/...`（ZLM 内部端口），nginx 没有重写这个 URL，导致浏览器绕过 nginx 直连不可达的端口 80。

---

## 数据流链路（实际发生的）

```
1. 浏览器 → nginx(:8080) → WVP: GET /api/play/start/{deviceId}/{channelId}
   ✅ SIP INVITE 成功，RTP 收流成功，ZLM 流注册成功

2. WVP 构造流地址（正确行为，按设计使用 httpPort）:
   ZLMMediaNodeServerService.java (line 666-668):
   streamInfoResult.setWsFlv(addr=streamIp, mediaServer.getHttpPort(), ...)
   → addr = stream_ip = 192.168.0.40
   → httpPort = 80（ZLM 实际 HTTP 端口）

3. WVP 返回 JSON 给 nginx:
   {
     "ws_flv": "ws://192.168.0.40:80/rtp/xxx.live.flv",
     "rtc":    "http://192.168.0.40:80/index/api/webrtc?..."
   }

4. nginx sub_filter 处理:
   ✅ downloadFile URL → 被重写为 nginx 相对路径
   ✅ mp4_record URL   → 被重写为 nginx 相对路径
   ❌ ws://...:80/rtp/  → 未被重写，原样透传
   ❌ http://...:80/index/api/webrtc → 未被重写，原样透传

5. 浏览器收到原始流 URL:
   ws://192.168.0.40:80/rtp/xxx.live.flv
   ❌ ZLM 的 80 端口未映射到宿主机，浏览器无法直接访问容器内部端口

6. ZLM 等待 20 秒无观众 → streamNoneReaderDelayMS=20000 → 关闭流
```

---

## 为什么这个问题发生在官方模板中

WVP 的架构设计：

### IP 分离机制（已实现）

| 配置项 | 用途 | 值 |
|--------|------|---|
| `media.ip` | WVP→ZLM 内部 API 调用 | `polaris-media`（Docker DNS） |
| `media.stream-ip` | 流 URL 的主机地址 | `192.168.0.40`（外部可达 IP） |
| `media.sdp-ip` | SIP SDP 中的地址 | `192.168.0.40`（摄像头可达 IP） |
| `media.hook-ip` | ZLM Hook 回调 WVP | `polaris-wvp`（Docker DNS） |

IP 分离是完整的：内部通信用 Docker DNS，外部 URL 用宿主机 IP。

### 端口分离机制（未完整实现）

WVP 只有一个 `http-port` 配置（如 80），同时用于：
- **内部 API 调用**：`http://polaris-media:80/index/api/...`
- **外部流 URL**：`ws://192.168.0.40:80/rtp/...`

这本身不是 bug——当 ZLM 的 HTTP 端口直接暴露时（官方 `deployment.md` 推荐的方式），同一端口内外都可用。

但当使用 nginx 代理时（官方 `docker-compose.yml` 提供的模式），需要通过 `sub_filter` 将内部端口（80）替换为 nginx 端口（8080）。官方 nginx 模板只对文件下载 URL 做了这个替换，**遗漏了流播放 URL**。

### 官方两种部署模式的对比

| 方面 | 直接暴露模式 | nginx 代理模式 |
|------|------------|---------------|
| ZLM HTTP 端口 | 映射到宿主机 | 不暴露 |
| 流 URL 可达性 | ✅ 直连 `host:80` | ❌ 需要 sub_filter 重写端口 |
| 文件下载 | ✅ 直连 | ✅ sub_filter 已覆盖 |
| 录像回放 | ✅ 直连 | ✅ sub_filter 已覆盖 |
| **实时流播放** | **✅ 直连** | **❌ sub_filter 未覆盖** |
| 安全性 | 低（ZLM 直接暴露） | 高（通过 nginx 统一入口） |

---

## 解决方案

### 方案 A：补全 nginx sub_filter 规则（推荐，符合官方设计意图）

官方 nginx 模板已提供了流代理基础设施（`/rtp/` + WebSocket 支持），只需添加缺失的 `sub_filter` 规则。

修改 `docker/nginx/templates/nginx.conf.template`，在 `/api/` location 的 sub_filter 区域添加：

```nginx
location  /api/ {
    proxy_set_header Host $http_host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header REMOTE-HOST $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_pass http://polaris-wvp:18978;

    # 防止响应被 gzip 压缩（sub_filter 不处理压缩内容）
    proxy_set_header Accept-Encoding "";

    set $original_host ${Stream_IP};

    # 已有：文件下载 URL 重写
    sub_filter "http://$original_host/index/api/downloadFile" "mediaserver/api/downloadFile";
    sub_filter "http://$original_host:80/index/api/downloadFile" "mediaserver/api/downloadFile";
    sub_filter "https://$original_host/index/api/downloadFile" "mediaserver/api/downloadFile";
    sub_filter "https://$original_host:443/index/api/downloadFile" "mediaserver/api/downloadFile";
    sub_filter "http://$original_host/mp4_record" "mp4_record";
    sub_filter "http://$original_host:80/mp4_record" "mp4_record";
    sub_filter "https://$original_host/mp4_record" "mp4_record";
    sub_filter "https://$original_host:443/mp4_record" "mp4_record";

    # 新增：流播放 URL 重写（核心修复）
    # 按具体路径精确匹配，避免全局替换影响非流 URL
    # WS-FLV / HTTP-FLV 流
    sub_filter "ws://$original_host:80/rtp/" "ws://$original_host:${WebHttp}/rtp/";
    sub_filter "http://$original_host:80/rtp/" "http://$original_host:${WebHttp}/rtp/";
    # WebRTC SDP 协商
    sub_filter "http://$original_host:80/index/api/webrtc" "http://$original_host:${WebHttp}/index/api/webrtc";
    # HLS
    sub_filter "http://$original_host:80/rtp/" "http://$original_host:${WebHttp}/rtp/";
    # 注意：不使用全局匹配 "http://host:80/"，避免误替换 ZLM API 回调地址等非流 URL

    sub_filter_once off;
    sub_filter_types application/json;
}
```

同时，nginx 需要添加 WebRTC API 代理路径：

```nginx
# WebRTC SDP 协商代理
location /index/api/webrtc {
    proxy_pass http://polaris-media:80/index/api/webrtc;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
}
```

**注意事项：**
- `sub_filter` 规则中 `${WebHttp}` 会被 Docker envsubst 替换为实际端口（8080）
- 必须添加 `proxy_set_header Accept-Encoding "";` 防止 gzip 压缩
- `sub_filter` 一次只能替换一个字符串，如果 URL 格式多变（有无端口、HTTP/HTTPS/WS/WSS），需要多条规则
- WebRTC 的 SDP 协商走 HTTP POST，需要独立的 location 代理
- **WebRTC 额外限制**：ZLM 的 WebRTC 实现在 SDP 响应中包含 ICE 候选地址（如 `192.168.0.40:8000`），这些地址不在 HTTP 响应体中，不会被 `sub_filter` 重写。需要配置 ZLM 的 `[rtc] externIP` 或通过 STUN/TURN 服务器解决。方案 A 对 WS-FLV/HTTP-FLV 协议完整，但对 WebRTC 可能不充分
- 每条 `sub_filter` 规则必须精确匹配具体路径前缀（如 `/rtp/`、`/index/api/webrtc`），避免全局替换 `http://host:80/` 影响回调地址等非流 URL

**此方案的优劣势：**
- ✅ 符合官方 nginx 代理的设计意图
- ✅ ZLM 不直接暴露，安全性好
- ✅ WS-FLV / HTTP-FLV / HLS / TS 等协议通过 nginx 统一入口
- ⚠️ sub_filter 对 JSON 中的 URL 做字符串替换，比较脆弱
- ⚠️ 新增流协议或端口变化时需要同步更新 sub_filter 规则
- ⚠️ WebRTC 的 ICE 候选地址不受 sub_filter 控制，需额外配置

### 方案 B：直接暴露 ZLM HTTP 端口（最简单）

遵循 `deployment.md` 的推荐，将 ZLM HTTP 端口直接映射到宿主机。

**变量关系说明：**

`docker-compose.yml` 已定义 `MediaHttp: ${WebHttp:-8080}`，`MediaHttp` 不是一个新变量，它来自 `.env` 中的 `WebHttp`。当前 `.env` 中 `WebHttp=8080`（nginx 端口），方案 B 需要将 ZLM HTTP 端口改为与 nginx 不同的值。

**1. `.env` — 修改 `WebHttp` 值（或新增 ZLM 专用端口变量）**

方式一：直接修改 `WebHttp`（会影响 nginx 监听端口，不推荐）
方式二：新增一个 ZLM 专用的端口变量（推荐）：

```diff
WebHttp=8080
+ ZlmHttp=8081
```

**2. `docker/media/config.ini` — 修改 ZLM HTTP 端口**

```diff
[http]
- port=80
+ port=8081
```

**3. `docker/docker-compose.yml` — 添加 ZLM HTTP 端口映射**

```yaml
polaris-media:
  ports:
    - "${ZlmHttp:-8081}:${ZlmHttp:-8081}/tcp"   # ZLM HTTP（新增）
    - "${MediaRtmp:-10001}:${MediaRtmp:-10001}/tcp"  # RTMP
    # ... 其余不变
```

**4. `docker/wvp/wvp/application-docker.yml` — 修改 `http-port`**

```diff
media:
-   http-port: 80
+   http-port: ${ZlmHttp:8081}
```

**修改后效果：**
- WVP API 调用：`http://polaris-media:8081/index/api/...` ✅（Docker 内网）
- 流 URL：`ws://192.168.0.40:8081/rtp/xxx.live.flv` ✅（宿主机端口映射）
- 浏览器直连 ZLM:8081，不需要 nginx 代理流

**此方案的优劣势：**
- ✅ 最简单，只改配置文件，不改代码
- ✅ 所有流协议（FLV/WS-FLV/WebRTC/HLS/TS/FMP4）全部可用
- ✅ 不依赖 sub_filter，无 URL 重写脆弱性
- ✅ WebRTC 的 ICE 候选地址也正确指向宿主机端口
- ⚠️ ZLM 直接暴露，安全性较低
- ⚠️ 多开一个宿主机端口

### 方案对比

| 维度 | 方案 A（补全 sub_filter） | 方案 B（暴露端口） |
|------|------------------------|------------------|
| 修改范围 | 仅 nginx 配置 | ZLM + WVP + docker-compose 配置 |
| 代码修改 | 无 | 无 |
| 安全性 | 高（统一 nginx 入口） | 中（ZLM 直接暴露） |
| WS-FLV/HTTP-FLV | ✅ sub_filter 可覆盖 | ✅ 全部可用 |
| HLS/TS/FMP4 | ✅ sub_filter 可覆盖 | ✅ 全部可用 |
| WebRTC | ⚠️ URL 可重写，但 ICE 候选地址不受 sub_filter 控制 | ✅ ICE 候选直接正确 |
| 维护性 | sub_filter 规则需随 URL 变化更新 | 1:1 端口映射，维护简单 |
| 符合度 | 符合官方 nginx 代理设计意图 | 符合官方 deployment.md 推荐 |
| 行业实践 | 大规模部署常用 nginx 统一入口 | 中小规模部署常用直连 |

**推荐：**
- **开发/测试环境**：方案 B（暴露端口），简单直接
- **生产环境**：方案 A（补全 sub_filter），安全性更好

---

## 涉及的关键文件清单

| 文件 | 作用 |
|------|------|
| `docker/nginx/templates/nginx.conf.template` | Nginx 代理配置，sub_filter 规则（缺失流 URL 重写） |
| `docker/.env` | 环境变量，Stream_IP=192.168.0.40 |
| `docker/docker-compose.yml` | Docker 编排，ZLM HTTP 端口未暴露 |
| `docker/media/config.ini` | ZLM 配置，http.port=80 |
| `docker/wvp/wvp/application-docker.yml` | WVP Docker 配置 |
| `src/main/java/.../media/zlm/ZLMMediaNodeServerService.java` | ZLM 流 URL 构造 |
| `src/main/java/.../conf/MediaConfig.java` | WVP 配置绑定 |
| `src/main/resources/配置详情.yml` | 完整配置说明 |
| `doc/_content/introduction/deployment.md` | 官方部署文档 |
| `doc/_content/introduction/config.md` | 官方配置文档 |

---

## 网络拓扑（当前状态）

```
┌─────────────────────────────────────────────────────────────────────┐
│                         宿主机 192.168.0.40                          │
│                                                                     │
│  ┌──────────┐                                                       │
│  │  浏览器   │                                                       │
│  └──┬───┬───┘                                                       │
│     │   │                                                           │
│     │   │ ws://192.168.0.40:80/rtp/xxx.live.flv  ← WVP 返回的 URL   │
│     │   │ ❌ 宿主机 80 端口未映射到 ZLM，不可达                        │
│     │   │                                                           │
│     │   │ 如果 sub_filter 补全后:                                     │
│     │   │ ws://192.168.0.40:8080/rtp/xxx.live.flv  ← 重写后的 URL    │
│     │   │ ✅ nginx:8080 → ZLM:80 可达                                │
│     │   │                                                           │
│     │ http://192.168.0.40:8080/api/...                              │
│     ▼                                                               │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │  Docker Network: media-net                                    │   │
│  │                                                              │   │
│  │  ┌─────────────┐     ┌─────────────┐     ┌──────────────┐  │   │
│  │  │ polaris-nginx│     │ polaris-wvp │     │polaris-media │  │   │
│  │  │  :8080      │     │  :18978     │     │  (ZLM)       │  │   │
│  │  │             │     │             │     │              │  │   │
│  │  │ /api/ ──────┼────►│             │────►│ :80  (HTTP)  │  │   │
│  │  │ /rtp/ ──────┼────────────────────────►│   内网✅ 外网❌│  │   │
│  │  │  (WS升级)   │     │  SIP 信令    │     │ :10003 (RTP) │  │   │
│  │  └─────────────┘     │             │     │   内网✅ 外网✅│  │   │
│  │       ▲              └──────┬──────┘     │ :10001 (RTMP)│  │   │
│  │       │                     │ Hook回调    │   内网✅ 外网✅│  │   │
│  │       │                     │ (Docker内网)│ :10002 (RTSP)│  │   │
│  │       │                     └───────────►│   内网✅ 外网✅│  │   │
│  │       │                                  └──────────────┘  │   │
│  └───────┼────────────────────────────────────────────────────┘   │
│          │                                                        │
│  宿主机端口映射:                                                   │
│  8080  → nginx:8080    (API + 前端)     ✅ 已映射                   │
│  80    → 无             (ZLM HTTP)      ❌ 未映射 ← 问题根源        │
│  10003 → media:10003   (RTP 收流)       ✅ 已映射                    │
│  10001 → media:10001   (RTMP)           ✅ 已映射                    │
│  10002 → media:10002   (RTSP)           ✅ 已映射                    │
│  18978 → wvp:18978     (WVP HTTP)       ✅ 已映射                    │
│  8160  → wvp:8160      (SIP)            ✅ 已映射                    │
│                                                                     │
│  ┌──────────┐                                                       │
│  │  摄像头   │──RTP────► 192.168.0.40:10003                         │
│  └──────────┘                                                       │
└─────────────────────────────────────────────────────────────────────┘

端口可达性说明：
- "内网✅" = Docker 内网通过容器名（如 polaris-media:80）可达
- "外网✅" = 通过宿主机 IP + 端口映射（如 192.168.0.40:10003）可达
- "外网❌" = 容器端口未映射到宿主机，外部无法访问
- ZLM 的 :80 端口只在 Docker 内网可达（polaris-media:80），宿主机外部不可达
```

---

## 前端协议选择与降级行为

`devicePlayer.vue` 中三个播放器 Tab 的协议选择逻辑：

```javascript
// devicePlayer.vue:355-359
player: {
    jessibuca: ['ws_flv', 'wss_flv'],
    webRTC:    ['rtc',   'rtcs'],
    h265web:   ['ws_flv', 'wss_flv']
}
```

URL 选择逻辑（`devicePlayer.vue:476-488`）：HTTP 页面取数组 `[0]`，HTTPS 页面取 `[1]`。

**协议选择流程：**
1. 用户选择播放器 Tab → 确定协议类型（如 jessibuca → `ws_flv`）
2. 从 WVP API 返回的 JSON 中读取对应字段（如 `streamInfo.ws_flv`）
3. 将 URL 直接传给播放器组件，**前端不做任何 IP/端口替换**

**前端没有任何端口推断或替换逻辑。** `getUrlByStreamInfo()` 只是按协议类型选择 JSON 中对应的 URL 字段，原样传给播放器。因此：
- 如果 WVP 返回 `ws://192.168.0.40:80/rtp/...`，前端就原样使用 `ws://192.168.0.40:80/rtp/...`
- 如果 `sub_filter` 补全后 WVP 返回的 URL 被改写为 `ws://192.168.0.40:8080/rtp/...`，前端也会原样使用

`PlayController.java` 中的 `useSourceIpAsStreamIp` 功能（默认关闭）仅替换 IP 不替换端口，且只在 WVP 侧生效，不涉及前端逻辑。

---

## 分析过程总结

本分析经历了三次迭代：

1. **第一版结论**：WVP 代码 Bug（ZLM 路径不使用 flvPort/wsFlvPort）
   - ❌ 被 challenge：WVP 是成熟开源项目，不可能有如此基本的代码缺陷

2. **第二版结论**：部署架构不匹配（不应使用 nginx 代理流媒体）
   - ❌ 被 web 搜索推翻：官方仓库本身就包含 nginx 代理流媒体的部署模式

3. **第三版结论**（当前）：官方 nginx 配置模板的 sub_filter 规则不完整
   - ✅ 官方 docker-compose.yml 注释掉 ZLM HTTP 端口，说明走 nginx 代理
   - ✅ 官方 nginx.conf.template 有完整的 `/rtp/` WebSocket 代理
   - ✅ 官方 nginx.conf.template 的 sub_filter 覆盖了 downloadFile 和 mp4_record
   - ❌ 官方 nginx.conf.template 的 sub_filter **遗漏了**流播放 URL（ws://...:80/rtp/、http://...:80/index/api/webrtc）
