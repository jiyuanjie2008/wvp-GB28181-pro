# 案例：发流端口未映射导致语音喊话/对讲无声音

> 创建时间：2026-06-30
> 影响范围：Docker 部署下的语音喊话（Broadcast）和语音对讲（Talk）——SIP 信令通但终端听不到声音
> 涉及组件：polaris-media (ZLM)、docker-compose.yml、`application-docker.yml`、终端设备

---

## 一、问题现象

点击"喊话"或"对讲"按钮后：

- 后端 API 返回 `code: 0`（成功），拿到 WebRTC 推流地址
- 浏览器成功将麦克风音频推到 ZLM（ZLM 日志显示 `RTC推流器` 注册成功）
- WVP 收到流注册后，发送 SIP INVITE 到终端
- **终端回复 200 OK**，WVP 发送 ACK，SIP 三向握手完成
- **但终端不出声**，一段时间后 ZLM 报 `RTC推流器结束推流`

---

## 二、日志证据

### WVP 日志——SIP 信令成功

```
[语音对讲] 开始
收流端口： 50502, 收流模式：TCP-PASSIVE
[语音喊话] 分配的ZLM为: polaris [polaris-media:50502]
...
[回复ack] 35020000201311005331-> 172.18.0.1:47655   ← 设备回复了200 OK
```

### ZLM 日志——推流器超时断开

```
RTC推流器(__defaultVhost__/talk/35020000201311005331_35020000201311005331)结束推流,耗时(s):150
接受rtp/rtcp/datachannel超时  ← 设备没连上来接收音频
```

---

## 三、代码路径分析

### 3.1 对讲（Talk）的发流机制

对讲模式下，WVP 调用 `PlayServiceImpl.talk()`（WVP 源码 `src/main/java/.../service/impl/PlayServiceImpl.java`）：

```java
// 发流：浏览器麦克风 → ZLM → 终端
mediaServerService.startSendRtpPassive(mediaServerItem, sendRtpInfo, ...);
// 收流：终端麦克风 → ZLM
sendRtpInfo.setReceiveStream(stream + "_talk");
receiveRtpServerService.addAuthenticateInfoForGb28181Talk(mediaServerItem, sendRtpInfo.getStream());
```

`startSendRtpPassive` 在 ZLM 上创建一个 TCP 监听端口，等待终端连接来接收音频。端口来自 `send-port-range` 配置。

### 3.2 端口配置链

`application-docker.yml` 定义了发流端口范围：

```yaml
media:
    rtp:
        send-port-range: 50502,50506     # 50502~50506 共5个端口
```

WVP 在启动时读取此配置，传递给 ZLM。当发起对讲时，WVP 从中选取一个端口（如 50502），在 ZLM 上打开 RTP 发送通道。

ZLM 生成 SIP INVITE 的 SDP，其中包含：

```
m=audio 50502 TCP/RTP/AVP 8
c=IN IP4 192.168.0.24
a=setup:passive
```

终端收到后，应主动 TCP 连接 `192.168.0.24:50502` 来接收音频。

### 3.3 端口映射缺失

但 `docker-compose.yml` 中 `polaris-media` 的 ports 配置**没有暴露 50502-50506 端口**：

```yaml
# ❌ 缺少发流端口映射
ports:
  - "6080:80/tcp"
  - "18000:18000/udp"
  - "31000-31500:31000-31500/tcp"    # 收流端口（已配）
  - "31000-31500:31000-31500/udp"    # 收流端口（已配）
  # 缺少：50502-50506:50502-50506/tcp
```

所以终端试图连接 `192.168.0.24:50502` 时，宿主机的 50502 端口未映射到 ZLM 容器，连接被拒。ZLM 等待终端连接直到超时（约 150 秒）。

---

## 四、根因

**`application-docker.yml` 配置了发流端口范围（`send-port-range: 50502,50506`），但 `docker-compose.yml` 的端口映射遗漏了这个范围，导致终端无法连接到 ZLM 的 RTP 发送端口。**

这是除 WebRTC 媒体口（`18000/udp`）之外的另一个需要暴露的端口范围：

| 端口范围 | 协议 | 用途 | 容易遗漏？ |
|---------|------|------|-----------|
| `6080` | TCP | ZLM HTTP API + WS-FLV 播流 | ✅ 通常会配 |
| `31000-31500` | TCP/UDP | 终端推视频到 ZLM（收流） | ✅ 通常会配 |
| `10003` | TCP/UDP | 终端推视频到 ZLM（单端口模式） | ⚠️ |
| **`50502-50506`** | **TCP** | **ZLM 发音频给终端（发流）** | ❌ **容易遗漏** |
| `18000` | UDP/TCP | WebRTC 音频推流 | ⚠️ 新增时易漏 |

---

## 五、修复

### 修复：`docker-compose.yml`

在 `polaris-media` 的 `ports` 中添加：

```yaml
- "50502-50506:50502-50506/tcp"    # [发流]RTP发送端口范围（喊话/对讲）
```

修改后需要**重建 ZLM 容器**（不是重启镜像）：

```bash
docker compose stop polaris-media
docker compose rm -f polaris-media
docker compose up -d polaris-media
```

> **注意**：`docker compose up -d` 会读取当前 docker-compose.yml 的 ports 配置创建新容器。如果遇到端口冲突，稍等再试即可。

---

## 六、连带发现：ZLM 容器端口映射丢失

在排查本问题的过程中，ZLM 容器在重建时出现了 `6080:80/tcp` 端口映射丢失的问题——重建时若端口冲突导致创建失败，重试后可能使用不完整的配置启动容器。

**后果**：6080 端口丢失后：
- WVP 无法通过 HTTP API 控制 ZLM（RTP 服务器无法创建/关闭）
- WS-FLV 和 WebRTC 流无法通过浏览器直接访问
- 表现为视频点播和喊话/对讲全部失败

**诊断方法**：确认容器端口映射是否完整：

```bash
docker port docker-polaris-media-1 | grep 6080
# 应输出: 80/tcp -> 0.0.0.0:6080
```

如果缺失，需要删除容器重新创建：

```bash
docker stop docker-polaris-media-1
docker rm docker-polaris-media-1
docker compose up -d polaris-media
```

---

## 七、经验总结

1. **三层端口都要检查**：WVP 的 RTP 配置（`application-docker.yml`）、ZLM 的端口配置（`config.ini`）、Docker 端口映射（`docker-compose.yml`）三层必须一致。任何一层遗漏都会导致功能异常。

2. **发流端口容易被忽略**：收流端口（终端→ZLM，如 31000-31500）通常都会配置，但发流端口（ZLM→终端，如 50502-50506）在配置对讲功能时容易遗忘。

3. **重建容器后验证端口映射**：`docker compose up -d` 重建容器后，建议用 `docker port <container>` 检查所有必要的端口映射是否完整，防止因端口冲突导致创建不完整。
