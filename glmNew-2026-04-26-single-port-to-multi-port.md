# RTP 收流模式切换：单端口 → 多端口

**日期**：2026-04-26
**背景**：单端口模式（`rtp_enable=0`）下，TCP-PASSIVE 设备第一次点播成功，后续全部失败。ZLM 报 `illegal connection` 拒绝 TCP 连接。
**根因**：单端口模式下 WVP 不调用 ZLM 的 `openRtpServer`，ZLM 的 rtp_proxy 端口无法可靠处理 TCP 收流。多端口模式下每路流创建独立 TCP 监听，彻底解决此问题。

---

## 修改清单

共 3 个文件、3 处修改 + 1 条数据库 SQL。

---

### 修改 1：WVP 配置启用多端口模式

**文件**：`docker/wvp/wvp/application-docker.yml`

**位置**：第 111 行

**当前**：

```yaml
  rtp:
    enable: false
    port-range: 30000,30500
    send-port-range: 50502,50506
```

**改为**：

```yaml
  rtp:
    enable: true
    port-range: 30000,30500
    send-port-range: 50502,50506
```

**说明**：`enable: true` 使 WVP 在每次点播时调用 ZLM 的 `openRtpServer` API，从 `port_range` 中分配独立端口。`port-range` 取值必须与 ZLM 配置的 `rtp_proxy.port_range` 一致。

---

### 修改 2：Docker 暴露 RTP 端口范围

**文件**：`docker/docker-compose.yml`

**位置**：`polaris-media` 服务的 `ports` 部分，第 71 行之后

**当前**：

```yaml
      - "${MediaRtp:-10000}:${MediaRtp:-10000}/tcp"   # [收流]RTP
      - "${MediaRtp:-10000}:${MediaRtp:-10000}/udp"   # [收流]RTP
```

**改为**：

```yaml
      - "${MediaRtp:-10000}:${MediaRtp:-10000}/tcp"   # [收流]RTP（单端口备用）
      - "${MediaRtp:-10000}:${MediaRtp:-10000}/udp"   # [收流]RTP（单端口备用）
      - "30000-30500:30000-30500/tcp"                  # [收流]RTP多端口模式
      - "30000-30500:30000-30500/udp"                  # [收流]RTP多端口模式
```

**说明**：

- 原有的 10003 端口映射保留，作为单端口模式切换回去时的备用
- `30000-30500` 是 ZLM 配置 `rtp_proxy.port_range` 中定义的范围，共 501 个端口
- TCP 和 UDP 都需要暴露，因为设备可能使用 UDP 或 TCP 传输
- Docker Compose 支持端口范围语法 `start-end:start-end`

**注意事项**：

- 确保 Windows 防火墙允许 30000-30500 端口范围的入站连接
- 确保 30000-30500 范围内的端口没有被其他程序占用
- 端口范围越大，可同时点播的路数越多（每个点播占用 1 个端口）

---

### 修改 3：数据库更新 MediaServer 的 rtp_enable

**执行方式**：进入 MySQL 容器执行 SQL

```sql
UPDATE wvp_media_server SET rtp_enable=1, rtp_port_range='30000,30500' WHERE id='polaris';
```

**执行命令**：

```bash
docker exec docker-polaris-mysql-1 mysql -uwvp_user -pwvp_password wvp \
  -e "UPDATE wvp_media_server SET rtp_enable=1, rtp_port_range='30000,30500' WHERE id='polaris';"
```

**说明**：

- `application-docker.yml` 中的 `rtp.enable` 只影响初始注册时的默认值
- MediaServer 记录持久化在数据库 `wvp_media_server` 表中
- 数据库中的 `rtp_enable` 字段才是运行时实际使用的值
- `rtp_port_range` 一并更新，确保数据库和配置文件中的端口范围一致，防止残留旧值
- 修改配置文件 + 数据库，两者保持一致

---

## 配置关系图

```
┌─────────────────────────────────────────────────────────────┐
│                    单端口模式 (修改前)                         │
│                                                             │
│  WVP: rtp.enable=false, rtp_enable=0                       │
│                                                             │
│  点播时:                                                     │
│    WVP → 直接使用 rtpProxyPort=10003                        │
│    WVP → 不调用 ZLM openRtpServer                           │
│    SDP: m=video 10003（所有流共用端口）                        │
│    ZLM: 靠 SSRC 区分流                                       │
│                                                             │
│  问题: TCP-PASSIVE 设备第二次点播后 ZLM 报 illegal connection  │
├─────────────────────────────────────────────────────────────┤
│                    多端口模式 (修改后)                         │
│                                                             │
│  WVP: rtp.enable=true, rtp_enable=1                        │
│  ZLM: rtp_proxy.port_range=30000-30500                     │
│  Docker: 暴露 30000-30500/tcp+udp                           │
│                                                             │
│  点播时:                                                     │
│    WVP → 调用 ZLM openRtpServer(port=0)                     │
│    ZLM → 从 30000-30500 分配端口（如 30001）                  │
│    SDP: m=video 30001（每路流独占端口）                        │
│    ZLM: 每路流有独立 TCP 监听器                               │
│                                                             │
│  优势: TCP/UDP 均可靠工作，无 illegal connection 问题         │
└─────────────────────────────────────────────────────────────┘
```

---

## 数据流变化

### 修改前（单端口，TCP-PASSIVE）

```
第1次点播:
  WVP → SDP: m=video 10003 TCP/RTP/AVP
  设备 → TCP连接到 192.168.0.24:10003 → ZLM 碰巧接受 → 播放成功 ✓

第2次点播:
  WVP → SDP: m=video 10003 TCP/RTP/AVP
  设备 → TCP连接到 192.168.0.24:10003 → ZLM 报 illegal connection → 失败 ✗
```

### 修改后（多端口，TCP-PASSIVE）

```
每次点播:
  WVP → 调用 ZLM openRtpServer → 分配端口 30001
  WVP → SDP: m=video 30001 TCP/RTP/AVP
  设备 → TCP连接到 192.168.0.24:30001 → ZLM 专用监听器 → 播放成功 ✓

下次点播:
  WVP → 调用 ZLM openRtpServer → 分配端口 30002
  WVP → SDP: m=video 30002 TCP/RTP/AVP
  设备 → TCP连接到 192.168.0.24:30002 → ZLM 专用监听器 → 播放成功 ✓
```

---

## 验证步骤

1. 执行修改 1、2、3

2. 重建并启动容器：

```bash
cd docker
docker compose down
docker compose up -d
```

3. 等待设备重新注册（心跳间隔内自动完成，或重启设备）

4. 浏览器登录 WVP 前端，点击设备通道的"播放"按钮

5. 第一次播放成功后，停止播放，再点第二次、第三次

6. 打开浏览器开发者工具（F12）→ Network 标签页，确认 WS 连接正常

7. 检查 ZLM 日志确认无 `illegal connection` 错误：

```bash
docker logs docker-polaris-media-1 2>&1 | grep "illegal"
```

---

## 端口占用汇总

| 端口 | 协议 | 用途 | 映射方式 |
|------|------|------|----------|
| 6080 | TCP | ZLM HTTP（浏览器播流） | 6080→80 |
| 8160 | TCP/UDP | SIP 信令 | 8160→8160 |
| 18978 | TCP | WVP HTTP API | 18978→18978 |
| 10001 | TCP/UDP | RTMP | 10001→10001 |
| 10002 | TCP/UDP | RTSP | 10002→10002 |
| 10003 | TCP/UDP | RTP 单端口备用 | 10003→10003 |
| 30000-30500 | TCP/UDP | **RTP 多端口模式（新增）** | 直通 |

---

## 回滚方案

如需回退到单端口模式：

1. 恢复 `application-docker.yml`：`enable: false`
2. 数据库：`UPDATE wvp_media_server SET rtp_enable=0 WHERE id='polaris';`
3. `docker-compose.yml` 中删除 30000-30500 端口行（或注释掉）
4. 重建容器：`docker compose down && docker compose up -d`

---

## 已知限制

| 限制 | 说明 |
|------|------|
| 端口范围决定并发路数 | 30000-30500 共 501 个端口，即最多同时 501 路点播 |
| 防火墙需放行 | Windows 防火墙需允许 30000-30500 入站 |
| 端口范围硬编码 | docker-compose、WVP 配置、ZLM 配置三处需保持一致 |
| Docker 端口映射开销 | 500+ 端口映射在 Windows Docker 上有轻微性能影响 |
