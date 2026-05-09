# WVP-ZLM-Nginx 播放地址异常 RCA 结论稿

## 1. 事件概述

在当前 Docker 部署环境下，前端调用 WVP 点播接口后，SIP INVITE、RTP 收流、流注册均成功，但浏览器侧播放失败。表现为：

- WVP 点播启动接口返回 `200 OK`
- 浏览器随后尝试建立 `WS-FLV/HTTP-FLV/WebRTC` 播放连接失败
- ZLMediaKit 在无观众超时后主动关闭流

本次 RCA 聚焦于“为什么点播启动成功，但浏览器仍无法播放”。

---

## 2. 最终结论

### 2.1 根因结论

本次故障的**根本原因是代码实现与部署架构不匹配**，并非单纯的环境参数填写错误。

在当前 Docker 架构中：

- **ZLM 内部 HTTP 管理地址**用于 WVP 后端访问 ZLM REST API
- **Nginx 对外地址**用于浏览器访问播放流

但当前 WVP 的 ZLM 实现路径中，构造前端播放地址时直接使用了：

- `streamIp` 作为主机地址
- `httpPort` 作为播放端口

这意味着代码将：

- **管理端口语义**
- **前端播放端口语义**

错误地复用了同一个 `httpPort` 字段。

在当前部署中，`httpPort=80` 实际对应的是 **ZLM 容器内部 HTTP 端口**；而浏览器真正可访问的对外入口是 **Nginx 暴露的 `8080`**。因此，WVP 返回给前端的播放地址指向了错误端口，导致浏览器无法连接。

### 2.2 一句话结论

> 当前故障不是“Nginx 没有代理 ZLM”，而是“WVP 在返回播放 URL 时绕过了 Nginx 的外部入口，错误返回了 ZLM 内部 HTTP 端口对应的地址”。

---

## 3. 影响范围

受影响能力包括：

- 基于 `HTTP-FLV` 的浏览器播放
- 基于 `WS-FLV` 的浏览器播放
- 基于 `WebRTC` 的浏览器播放
- 依赖 WVP 返回播放地址的前端实时点播链路

不直接受影响的能力包括：

- SIP 点播信令建立
- RTP 收流
- ZLM 内部流注册
- 容器内部 WVP -> ZLM 管理接口调用

---

## 4. 实际数据流与异常发生点

### 4.1 实际链路

当前 Docker 部署下，实际链路应为：

1. 设备/摄像头 -> ZLM
2. 浏览器 -> Nginx
3. Nginx -> 反向代理到 ZLM
4. ZLM -> 返回流数据给 Nginx
5. Nginx -> 转发给浏览器

也就是说，**浏览器拉流访问的应该是 Nginx 的对外地址，而不是 ZLM 的内部 HTTP 地址**。

### 4.2 实际故障链路

实际发生的是：

1. 浏览器调用 WVP 点播接口 `/api/play/start/`
2. WVP 成功发起 SIP INVITE，ZLM 收到 RTP 并成功注册流
3. WVP 构造播放地址时，使用 `streamIp + httpPort`
4. 前端拿到的地址为：

```text
ws://192.168.0.40:80/rtp/{stream}.live.flv
http://192.168.0.40:80/rtp/{stream}.live.flv
```

5. 浏览器尝试访问宿主机 `192.168.0.40:80`
6. 该端口并非当前对外播流入口，连接失败
7. ZLM 在 `streamNoneReaderDelayMS=20000` 超时后判定无人观看，关闭流

---

## 5. 直接证据链

## 5.1 代码证据：ZLM 路径下播放地址使用 `httpPort`

文件：

`src/main/java/com/genersoft/iot/vmp/media/zlm/ZLMMediaNodeServerService.java`

关键逻辑位于 `getStreamInfoByAppAndStream(...)`，约 `625-668` 行：

- 当 `addr == null` 时，取 `mediaServer.getStreamIp()`
- `setFlv(...)` 使用 `mediaServer.getHttpPort()`
- `setWsFlv(...)` 使用 `mediaServer.getHttpPort()`

即：

```java
addr = mediaServer.getStreamIp();
streamInfoResult.setFlv(addr, mediaServer.getHttpPort(), mediaServer.getHttpSSlPort(), flvFile);
streamInfoResult.setWsFlv(addr, mediaServer.getHttpPort(), mediaServer.getHttpSSlPort(), flvFile);
```

该实现直接证实：**ZLM 路径返回的 FLV/WS-FLV 地址使用的是 `streamIp + httpPort`。**

---

## 5.2 代码证据：`StreamInfo` 不会做端口转换

文件：

`src/main/java/com/genersoft/iot/vmp/common/StreamInfo.java`

约 `129-143` 行：

- `setFlv()` 直接生成 `http://host:port/file`
- `setWsFlv()` 直接生成 `ws://host:port/file`

说明一旦上游传入 `port=80`，返回给前端的就是 `:80`，中间不存在自动替换为 Nginx 对外端口的逻辑。

---

## 5.3 代码证据：ZLM 路径忽略 `flvPort/wsFlvPort`

文件：

- `src/main/java/com/genersoft/iot/vmp/media/bean/MediaServer.java`
- `src/main/java/com/genersoft/iot/vmp/media/abl/ABLMediaNodeServerService.java`
- `src/main/java/com/genersoft/iot/vmp/media/zlm/ZLMMediaNodeServerService.java`

结论：

- `MediaServer` 中虽然定义了 `flvPort`、`wsFlvPort`
- ABL 实现路径会使用这两个字段构造播放地址
- **ZLM 实现路径没有使用这两个字段，而是统一使用 `httpPort`**

这说明：

> 当前系统中“存在外部播放端口字段”并不代表 ZLM 路径会使用它们。

---

## 5.4 部署证据：ZLM 的 80/443 未对宿主机暴露

文件：

`docker/docker-compose.yml`

`polaris-media` 服务的 `ports` 配置中：

```yaml
#- "6080:80/tcp"
#- "4443:443/tcp"
```

可以确认：

- ZLM 容器内部 `80/443` 存在
- 但当前对宿主机并未直接暴露

因此浏览器访问 `宿主机IP:80` 并不能命中当前用于播流的 ZLM 服务入口。

---

## 5.5 部署证据：Nginx 对外暴露 8080，并代理 `/rtp/`

文件：

- `docker/docker-compose.yml`
- `docker/nginx/templates/nginx.conf.template`

关键事实：

1. `polaris-nginx` 暴露：

```yaml
- "${WebHttp:-8080}:8080"
```

2. Nginx 配置监听：

```nginx
listen 8080;
```

3. Nginx 对 `/rtp/` 的代理配置：

```nginx
location ^~ /rtp/ {
    proxy_pass http://polaris-media:80;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
}
```

该证据链明确表明：

- 外部浏览器访问入口是 **Nginx 8080**
- Nginx 会将 `/rtp/` 请求反向代理到 **ZLM 容器内 80 端口**
- 因此浏览器正确访问的地址应为：

```text
http://<服务器IP>:8080/rtp/...
ws://<服务器IP>:8080/rtp/...
```

而不是：

```text
http://<服务器IP>:80/rtp/...
ws://<服务器IP>:80/rtp/...
```

---

## 5.6 配置证据：当前配置同时存在内部管理端口和外部播放端口语义

文件：

`docker/wvp/wvp/application-docker.yml`

当前关键配置：

```yaml
media:
  ip: ${ZLM_HOST:127.0.0.1}
  http-port: 80
  stream-ip: ${Stream_IP}
  flv-port: ${MediaHttp:}
  ws-flv-port: ${MediaHttp:}
  auto-config: true
```

这组配置体现出两种不同语义：

- `media.ip + http-port`：用于 WVP 后端访问 ZLM
- `stream-ip + flv-port/ws-flv-port`：适合返回给外部浏览器的播放地址

但当前 ZLM 代码实现没有区分这两类端口用途，导致外部播放地址被错误地使用了 `httpPort`。

---

## 5.7 运行时证据：无人观看超时关闭流

文件：

`docker/media/config.ini`

配置：

```ini
streamNoneReaderDelayMS=20000
```

说明 ZLM 在 20 秒无观看者时会自动关闭流。这与故障现象完全一致：

- 浏览器因 URL 错误无法连上
- ZLM检测到无观众
- 20 秒后关闭流

---

## 5.8 前端证据：三种播放窗口取址逻辑不同，但都来自同一份 `streamInfo`

文件：

- `web/src/views/dialog/devicePlayer.vue`
- `web/src/views/common/channelPlayer/index.vue`
- `web/src/views/common/channelPlayer/jtDevicePlayer.vue`

当前前端播放器映射为：

```js
player: {
  jessibuca: ['ws_flv', 'wss_flv'],
  webRTC: ['rtc', 'rtcs'],
  h265web: ['ws_flv', 'wss_flv']
}
```

同时，前端通过 `getUrlByStreamInfo()` 按当前页面协议选择地址：

- 当前页面是 `http`：取每个播放器配置中的第 1 个字段
- 当前页面是 `https`：取每个播放器配置中的第 2 个字段
- 如果后端返回了 `transcodeStream`，则三个播放器都会优先使用 `transcodeStream` 中的地址

三种播放窗口的实际取址差异如下：

- **Jessibuca**
  - `http` 页面取 `ws_flv`
  - `https` 页面取 `wss_flv`
- **WebRTC**
  - `http` 页面取 `rtc`
  - `https` 页面取 `rtcs`
- **h265web**
  - `http` 页面取 `ws_flv`
  - `https` 页面取 `wss_flv`

这说明：

> **Jessibuca 与 h265web 使用同一类 `WS-FLV` 地址；WebRTC 使用独立的 `RTC` 地址。三者虽然字段不同，但都来自同一份后端返回的 `streamInfo`。**

---

## 5.9 为什么三种播放窗口会同时失败

文件：

- `src/main/java/com/genersoft/iot/vmp/media/zlm/ZLMMediaNodeServerService.java`
- `src/main/java/com/genersoft/iot/vmp/common/StreamInfo.java`
- `docker/nginx/templates/nginx.conf.template`

已确认：

- `ws_flv/wss_flv` 由 `setWsFlv(...)` 构造，底层统一使用 `mediaServer.getHttpPort()`
- `rtc/rtcs` 由 `setRtc(...)` 构造，底层同样统一使用 `mediaServer.getHttpPort()`
- `setRtc(...)` 生成的路径是：`/index/api/webrtc?app=...&stream=...&type=play`

因此三类窗口的失败原因可以拆解为：

- **Jessibuca 失败原因**
  - 依赖 `ws_flv/wss_flv`
  - 当前后端返回的地址实际为 `ws://<streamIp>:80/rtp/...` 或 `wss://<streamIp>:<sslPort>/rtp/...`
  - 当前 Docker 部署对外可用入口不是 `:80`，而是 Nginx 的 `:8080`
  - 因此浏览器无法建立 `WS-FLV` 连接
- **h265web 失败原因**
  - 与 Jessibuca 使用相同的 `ws_flv/wss_flv` 字段
  - 本质上是“同地址，不同播放器”
  - 所以只要 `WS-FLV` 地址错了，h265web 也会一起失败
- **WebRTC 失败原因**
  - 依赖 `rtc/rtcs`
  - 当前后端返回的地址实际为 `http://<streamIp>:80/index/api/webrtc?...` 或 `https://<streamIp>:<sslPort>/index/api/webrtc?...`
  - 该地址首先复用了错误的 `httpPort`
  - 此外，当前 Nginx 模板中能确认存在 `/rtp/` 的代理，但**没有看到 `/index/api/webrtc` 或 `/index/api/` 指向 `polaris-media:80` 的代理规则**
  - 因此 WebRTC 不仅存在“端口错误”问题，还存在“对外入口路径未代理”的额外阻断风险

综上：

> **三种窗口全部失败，不是三个播放器分别故障，而是它们共同依赖的播放地址源头存在统一错误，其中 WebRTC 还额外依赖 `/index/api/webrtc` 的可达性。**

---

## 6. 根因分类判断

### 6.1 是否属于环境配置错误

**不属于单纯的环境配置错误。**

原因是：

- 当前 Nginx 代理关系本身是成立的
- Docker 暴露方式也符合“ZLM 内网、Nginx 对外”的常见部署设计
- 出问题的关键在于：**代码没有正确区分管理地址和播放地址的生成逻辑**

### 6.2 是否属于代码实现问题

**属于。**

更准确地说，是：

- **代码实现假设 `httpPort` 既可用于管理调用，也可用于浏览器播放**
- 该假设在“前端直连 ZLM”的部署下可能成立
- 在“ZLM 内部、Nginx 对外”的部署下不成立

因此，本次故障应归类为：

> **代码实现与部署拓扑不匹配导致的播放地址构造错误。**

---

## 7. 关于 `auto-config` 的审慎说明

当前代码中可以确认：

- `autoConfig` 字段存在
- 该字段会被配置和持久化

但基于本次核查到的代码，**尚未找到充分证据证明 `auto-config=true` 会把数据库中的 `httpPort` 自动重新覆盖回 ZLM 的 `80`**。

因此，下列说法不建议作为最终 RCA 结论的一部分：

> “只要不关闭 `auto-config`，`httpPort` 就一定会被重新改回 80。”

该判断目前证据不足，应单独作为待验证项，而非最终结论。

---

## 8. 修复方向建议

以下内容为**修复建议**，用于实施参考和方案评审，**本文档不执行任何修复动作**。

### 8.1 正确修复方向（推荐）

修改代码，使 ZLM 路径在构造播放地址时：

- WVP -> ZLM 管理接口继续使用内部地址：`media.ip + httpPort`
- 返回给前端的播放地址使用外部可访问入口：
  - `streamIp + flvPort`
  - `streamIp + wsFlvPort`
  - 或引入明确的“public/play port”语义字段
- 返回给前端的 `rtc/rtcs` 地址也必须使用**浏览器可访问**的 WebRTC 协商入口，而不能继续默认复用内部 `httpPort`
- 如果浏览器侧 WebRTC 要继续走 Nginx，则需要在 Nginx 中增加 `/index/api/webrtc` 或 `/index/api/` 到 `polaris-media:80` 的反向代理

这是最符合当前架构的根治方案。

### 8.2 可作为临时止血的环境侧改法

理论上可以通过网关层或端口暴露方式绕过该问题，例如：

- 让外部也能访问 ZLM 的 `80`
- 或让 WVP 同时通过 Nginx 访问 `/index/api/*`
- 或将对外播放入口与 WebRTC 协商入口显式拆分后分别配置

但这些方案本质上属于**绕过代码设计缺陷**，复杂度更高，且可能引入新的管理链路风险，不建议作为长期方案。

### 8.3 建议的修复实施顺序

为降低一次性改动风险，建议按以下顺序实施：

1. **先修复 `WS-FLV` 播放地址构造**
   - 让 `ws_flv/wss_flv` 返回外部可达的播放地址
   - 优先恢复 Jessibuca 与 h265web 两个窗口
2. **再修复 WebRTC 协商入口**
   - 让 `rtc/rtcs` 指向浏览器可达入口
   - 同时补齐 Nginx 对 `/index/api/webrtc` 的代理，或明确改为前端直连可访问的 ZLM 地址
3. **最后回归验证三种窗口**
   - 分别验证 Jessibuca、h265web、WebRTC
   - 避免只验证一个窗口即误判问题已全部解决

---

## 9. 最终 RCA 定稿

### 9.1 最终结论

本次浏览器播放失败的根因，不是 SIP 点播失败，也不是 ZLM 未收流，而是 **WVP 在 ZLM 路径下构造播放 URL 时错误使用了 ZLM 内部 `httpPort`，导致前端拿到不可达的播放地址**。

### 9.2 最终定性

> **根因定性：代码实现问题。**
>
> **问题触发条件：当前 Docker/Nginx 部署将 ZLM 作为内部服务，仅通过 Nginx 对外提供播流入口。**
>
> **直接故障表现：WVP 返回给前端的播放地址未使用 Nginx 的外部端口，导致浏览器无法建立观看连接。**

---

## 10. 附：当前部署下正确的职责划分

### 10.1 浏览器播放地址

应返回：

```text
http://<服务器IP>:8080/rtp/...
ws://<服务器IP>:8080/rtp/...
```

### 10.2 WVP 调 ZLM 管理接口地址

应使用：

```text
http://polaris-media:80/index/api/...
```

### 10.3 原则

> **播放地址返回“客户端可访问地址”；管理地址使用“服务内部可访问地址”。**

---

## 11. 如何在浏览器 Network 里验证这三个地址分别错在哪

### 11.1 通用验证入口

建议统一按以下顺序观察：

1. 打开浏览器开发者工具 `F12`
2. 进入 `Network`
3. 勾选 `Preserve log`
4. 先触发一次点播
5. 先查看 `/api/play/start/` 或对应点播接口响应体中的 `streamInfo`
6. 再切换不同播放窗口，观察实际发出的网络请求

首先要验证的不是播放器报错，而是 **WVP 返回给前端的地址字段本身是否正确**。

### 11.2 先看点播接口响应体中的地址字段

在点播接口响应中，重点检查以下字段：

- `ws_flv`
- `wss_flv`
- `rtc`
- `rtcs`

判断标准如下：

- **Jessibuca / h265web 正确示例**

```text
ws://<服务器IP>:8080/rtp/<stream>.live.flv
```

- **Jessibuca / h265web 错误示例**

```text
ws://<服务器IP>:80/rtp/<stream>.live.flv
```

- **WebRTC 正确示例（走 Nginx 时）**

```text
http://<服务器IP>:8080/index/api/webrtc?app=...&stream=...&type=play
```

- **WebRTC 错误示例**

```text
http://<服务器IP>:80/index/api/webrtc?app=...&stream=...&type=play
```

如果在这一层已经看到了错误端口，那么后续三个播放器失败就已经有了直接解释。

### 11.3 Jessibuca 窗口如何验证

验证步骤：

1. 切换到 `Jessibuca` 播放窗口
2. 在 `Network` 中筛选 `WS`
3. 观察建立的 WebSocket 连接地址

应重点看：

- 请求 URL 是否为 `ws://<服务器IP>:8080/rtp/...`
- 如果是 `http` 页面，是否错误连到了 `ws://<服务器IP>:80/rtp/...`
- 握手是否返回 `101 Switching Protocols`

错误判定：

- 如果请求直接是 `:80/rtp/...`，则说明 `ws_flv` 字段错误
- 如果请求到了 `:8080/rtp/...`，但没有 `101`，则应继续检查 Nginx 的 WebSocket 升级配置与目标流状态

### 11.4 h265web 窗口如何验证

h265web 与 Jessibuca 使用相同的 `ws_flv/wss_flv` 字段，因此验证方式基本一致：

1. 切换到 `h265web` 播放窗口
2. 在 `Network` 中筛选 `WS`
3. 观察 WebSocket 连接地址

判断原则：

- 如果 h265web 的请求地址与 Jessibuca 相同，则说明两者是“同地址，不同播放器”
- 若该地址本身错误，则两者同时失败是必然现象

### 11.5 WebRTC 窗口如何验证

验证步骤：

1. 切换到 `WebRTC` 播放窗口
2. 在 `Network` 中筛选 `Fetch/XHR`
3. 查找 `/index/api/webrtc` 请求

应重点看：

- 请求 URL 是否指向 `http://<服务器IP>:8080/index/api/webrtc?...`
- 是否仍然错误指向 `http://<服务器IP>:80/index/api/webrtc?...`
- 如果已改成 `:8080`，返回是否是 `404/502/504`

错误判定：

- **请求到 `:80/index/api/webrtc` 失败**
  - 说明 `rtc` 字段本身错误
- **请求到 `:8080/index/api/webrtc` 但返回 `404` 或 `502`**
  - 说明即使端口改成外部入口，Nginx 仍未正确代理该路径
- **根本没有出现 `/index/api/webrtc` 请求**
  - 说明播放器在前置地址解析或协议准备阶段就失败了，需要回看 `streamInfo.rtc/rtcs`

### 11.6 三个窗口的验证结论如何落地

理想情况下，三类窗口的验证结果应整理为下表：

- **Jessibuca**
  - 实际请求：`ws://...`
  - 结论：`ws_flv/wss_flv` 是否错误
- **h265web**
  - 实际请求：`ws://...`
  - 结论：是否与 Jessibuca 共用同一错误地址
- **WebRTC**
  - 实际请求：`http://.../index/api/webrtc?...`
  - 结论：是 `rtc/rtcs` 字段错误，还是 Nginx 未代理 WebRTC 入口，或两者同时存在

通过这一步，可以把“播放器播放失败”精确拆分成：

- **前端拿错地址**
- **外部入口未代理**
- **两者同时存在**
