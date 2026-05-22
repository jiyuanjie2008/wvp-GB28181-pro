# 案例：Nginx 代理 ZLM 媒体流的端口转换机制

> 创建时间：2026-05-21
> 影响范围：Docker 部署下的前端视频播流、录像回放、文件下载
> 涉及组件：polaris-nginx、polaris-media (ZLM)、polaris-wvp

---

## 一、背景

在 Docker 部署中，ZLMediaKit (ZLM) 容器内部 HTTP 端口为 **80**，但容器内部端口无法被前端直接访问。需要通过 Nginx 反向代理和 Docker 端口映射，让浏览器能正确访问媒体流。

### 核心矛盾

| 角色 | 地址 | 可达性 |
|------|------|--------|
| ZLM 容器内部 | `polaris-media:80` | 仅 Docker 网络内可达 |
| Docker 主机映射 | `主机IP:6080` → 容器 80 | 前端可直连 |
| Nginx 代理 | `主机IP:8090/rtp/` → ZLM:80 | 前端可通过 Nginx 访问 |
| WVP 返回的流 URL | `http://主机IP:80/rtp/...` | **端口 80 对前端不可达** |

WVP 生成的流 URL 使用 ZLM 的内部端口 80，浏览器无法直接访问。Nginx 需要在中间做端口转换。

---

## 二、解决方案：双管齐下

Nginx 使用 **反向代理** + **响应内容改写** 两种策略配合解决。

### 策略一：反向代理路径（容器网络直通）

Nginx 和 ZLM 在同一个 Docker bridge 网络 `media-net` 中，可以通过容器名直接通信。

**配置**（`docker/nginx/templates/nginx.conf.template`）：

```nginx
# WebSocket 播流代理
location ^~ /rtp/ {
    proxy_pass http://polaris-media:80;

    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";

    proxy_connect_timeout 60s;
    proxy_read_timeout 3600s;
    proxy_send_timeout 60s;
}

# 录像文件代理
location ^~ /mp4_record/ {
    proxy_pass http://polaris-media:80;
    # ... 同上配置
}

# ZLM 文件下载代理
location /mediaserver/api/downloadFile {
    proxy_pass http://polaris-media:80/index/api/downloadFile;
    # ... 同上配置
}
```

**访问链路**：

```
浏览器 → http://IP:8090/rtp/xxx.live.flv
       → Nginx (容器 8080 → 主机 8090)
       → polaris-media:80 (容器内部直通，无需端口映射)
```

这种方式下，前端只需访问 Nginx 的 8090 端口，Nginx 通过容器网络直接转发到 ZLM 的 80 端口。WebSocket 升级头也一并传递，确保 ws-flv 播流正常工作。

### 策略二：`sub_filter` JSON 响应改写

当 WVP 的 API（如 `/api/play/start/`）返回流 URL 时，URL 中包含 ZLM 的内部端口 80。Nginx 在 `/api/` location 中使用 `sub_filter` 对 JSON 响应做文本替换。

**配置**：

```nginx
location /api/ {
    proxy_pass http://polaris-wvp:18978;

    set $original_host ${Stream_IP};    # 从环境变量注入，如 192.168.0.40

    # 端口改写：内部 80 → 对外 6080
    sub_filter "http://$original_host:80/" "http://$original_host:6080/";
    sub_filter "ws://$original_host:80/"   "ws://$original_host:6080/";

    # 路径改写：绝对 URL → Nginx 代理的相对路径
    sub_filter "http://$original_host:80/index/api/downloadFile" "mediaserver/api/downloadFile";
    sub_filter "http://$original_host:80/mp4_record"             "mp4_record";

    sub_filter_once off;
    sub_filter_types application/json;
}
```

**Docker 端口映射配合**（`docker-compose.yml`）：

```yaml
polaris-media:
    ports:
        - "6080:80/tcp"    # 主机 6080 → ZLM 容器 80
```

**改写效果**：

```
WVP 返回:  ws://192.168.0.40:80/rtp/xxx.live.flv       ← 端口 80，前端不可达
改写后:    ws://192.168.0.40:6080/rtp/xxx.live.flv      ← 端口 6080，前端可直连 ZLM
```

---

## 三、整体数据流图

```
                        Docker Bridge: media-net
┌──────────┐            ┌──────────────┐            ┌──────────────┐
│          │            │  polaris-    │            │  polaris-    │
│  浏览器   │            │  nginx       │            │  media (ZLM) │
│          │───────────→│  :8080       │───────────→│  :80         │
│          │   :8090    │              │  容器网络    │              │
│          │   (主机)    │              │  直通       │              │
│          │            │  /rtp/ ──── proxy_pass ──→ │              │
│          │            │  /mp4_record/ proxy_pass ─→│              │
│          │            │  /api/ → WVP + sub_filter  │              │
│          │            └──────────────┘            └──────┬───────┘
│          │                                                │
│          │         Docker 端口映射: 6080:80               │
│          │←───────────────────────────────────────────────┘
│          │   直连: ws://IP:6080/rtp/xxx.live.flv
│          │   (sub_filter 改写后前端使用此地址)
└──────────┘

┌──────────┐            ┌──────────────┐
│          │            │  polaris-    │
│  浏览器   │            │  wvp         │
│          │───────────→│  :18978      │
│          │            │              │
│          │            │  生成流 URL   │
│          │            │  (含 :80 端口) │
│          │            └──────────────┘
└──────────┘
```

---

## 四、前端访问路径对比

| 场景 | 前端请求地址 | 经过路径 | 到达 ZLM |
|------|------------|---------|----------|
| Jessibuca ws-flv 播流 | `ws://IP:6080/rtp/xxx.live.flv` | Docker 端口映射 6080→80 | 直达 |
| Nginx 代理播流 | `http://IP:8090/rtp/xxx.live.flv` | Nginx → 容器网络 polaris-media:80 | 代理 |
| 录像回放 | `http://IP:8090/mp4_record/xxx.mp4` | Nginx → 容器网络 polaris-media:80 | 代理 |
| 文件下载 | `http://IP:8090/mediaserver/api/downloadFile?...` | Nginx → polaris-media:80/index/api/... | 代理 |
| HLS 流 | `http://IP:6080/rtp/xxx/hls.m3u8` | Docker 端口映射 6080→80 | 直达 |

---

## 五、关键配置文件索引

| 文件 | 职责 |
|------|------|
| `docker/.env` | 定义 `Stream_IP`、`WebHttp` 等环境变量 |
| `docker/nginx/templates/nginx.conf.template` | Nginx 反向代理规则 + sub_filter 改写 |
| `docker/media/config.ini` | ZLM 配置，`[http] port=80` |
| `docker/docker-compose.yml` | 端口映射 `6080:80`，网络 `media-net` |

---

## 六、注意事项

1. **`Stream_IP` 必须正确**：`.env` 中的 `Stream_IP` 决定了 sub_filter 匹配的目标。如果 WVP 生成的流 URL 中的 IP 与 `Stream_IP` 不一致，sub_filter 不会生效，前端拿到的仍是不可达的地址。

2. **两种路径可共存**：前端可以同时通过 Nginx 代理（8090）和 Docker 直连（6080）访问 ZLM。sub_filter 改写后的 URL 走直连，Jessibuca 的 WebSocket 播流即是此路径。

3. **WebSocket 必须配置 Upgrade 头**：`/rtp/` location 中 `proxy_http_version 1.1` + `Upgrade` + `Connection "upgrade"` 三项缺一不可，否则 WebSocket 握手失败。

4. **`proxy_read_timeout` 需设大**：视频流的 WebSocket 连接长时间保持，`/rtp/` 设置了 3600s（1小时），避免 Nginx 中途断开。
