# 流媒体 URL 端口修复方案

**日期**：2026-04-26
**问题**：WVP 生成的流 URL 端口为 80，但宿主机 80 端口被 Windows System 占用，浏览器无法访问。ZLM HTTP 端口需通过 6080 暴露，需要 Nginx 在 API 响应中改写端口号。
**文档关联**：详细根因分析见 `glmNew-gb28181-device-play-end-to-end-flow.md` 第 4.6 节

---

## 修改清单

共 2 个文件、3 处修改。

---

### 修改 1：暴露 ZLM HTTP 端口

**文件**：`docker/docker-compose.yml`

**位置**：第 62 行

**当前**：

```yaml
     #- "6080:80/tcp"     # [播流]HTTP  安全考虑-非测试阶段需要注释掉，改为由nginx代理播流地址
```

**改为**：

```yaml
      - "6080:80/tcp"     # [播流]HTTP 暴露ZLM HTTP端口供浏览器直接访问流媒体
```

---

### 修改 2：防止 gzip 压缩导致 sub_filter 失效

**文件**：`docker/nginx/templates/nginx.conf.template`

**位置**：`location /api/` 块内，`proxy_pass` 之前

**当前**：

```nginx
    location  /api/ {
        proxy_set_header Host $http_host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header REMOTE-HOST $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_pass http://polaris-wvp:18978;
```

**改为**：

```nginx
    location  /api/ {
        proxy_set_header Host $http_host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header REMOTE-HOST $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header Accept-Encoding "";
        proxy_pass http://polaris-wvp:18978;
```

**说明**：`Accept-Encoding ""` 告知 WVP 不要返回 gzip 压缩响应。否则 Nginx 的 `sub_filter` 模块无法在压缩的二进制数据中做字符串替换，后续的端口改写规则会静默失效。

---

### 修改 3：添加 sub_filter 端口改写规则

**文件**：`docker/nginx/templates/nginx.conf.template`

**位置**：`location /api/` 块内，现有 `sub_filter` 规则之后（第 36 行之后）

**当前**：

```nginx
        sub_filter "https://$original_host:443/mp4_record" "mp4_record";
        
        # 设置为off表示替换所有匹配项，而不仅仅是第一个
        sub_filter_once off;
```

**改为**：

```nginx
        sub_filter "https://$original_host:443/mp4_record" "mp4_record";

        # 端口改写：将流URL中的:80替换为:6080（ZLM HTTP通过6080对外暴露）
        sub_filter "http://$original_host:80/" "http://$original_host:6080/";
        sub_filter "ws://$original_host:80/" "ws://$original_host:6080/";

        # 设置为off表示替换所有匹配项，而不仅仅是第一个
        sub_filter_once off;
```

**说明**：

- `http://IP:80/` 匹配 HTTP-FLV、HLS、TS、FMP4、WebRTC 信令的 URL
- `ws://IP:80/` 匹配 WS-FLV、WS-FMP4、WS-HLS、WS-TS 的 URL
- 不需要改 `https://` 和 `wss://` 变体，因为当前 `http-ssl-port: 0`（SSL 未启用），不会生成 HTTPS/WSS 的流 URL
- 不需要改不带端口号的 `http://IP/` 规则，因为 WVP 的 `StreamURL.toString()` 总是拼接显式端口号（`protocol://host:port/file`）

---

## 修改后的完整 location /api/ 块

```nginx
    location  /api/ {
        proxy_set_header Host $http_host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header REMOTE-HOST $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header Accept-Encoding "";
        proxy_pass http://polaris-wvp:18978;


        # 从环境变量获取原始主机地址（x.x.x.x）
        set $original_host ${Stream_IP};

        # 执行字符串替换
        # 将媒体资源文件替换为Nginx输出的相对地址
        sub_filter "http://$original_host/index/api/downloadFile" "mediaserver/api/downloadFile";
        sub_filter "http://$original_host:80/index/api/downloadFile" "mediaserver/api/downloadFile";
        sub_filter "https://$original_host/index/api/downloadFile" "mediaserver/api/downloadFile";
        sub_filter "https://$original_host:443/index/api/downloadFile" "mediaserver/api/downloadFile";
        sub_filter "http://$original_host/mp4_record" "mp4_record";
        sub_filter "http://$original_host:80/mp4_record" "mp4_record";
        sub_filter "https://$original_host/mp4_record" "mp4_record";
        sub_filter "https://$original_host:443/mp4_record" "mp4_record";

        # 端口改写：将流URL中的:80替换为:6080（ZLM HTTP通过6080对外暴露）
        sub_filter "http://$original_host:80/" "http://$original_host:6080/";
        sub_filter "ws://$original_host:80/" "ws://$original_host:6080/";

        # 设置为off表示替换所有匹配项，而不仅仅是第一个
        sub_filter_once off;

        # 确保响应被正确处理
        sub_filter_types application/json;  # 只对JSON响应进行处理
    }
```

---

## 数据流变化

### 修改前

```
WVP 生成:  ws://192.168.0.40:80/rtp/xxx.live.flv
                    ↓
浏览器拿到: ws://192.168.0.40:80/rtp/xxx.live.flv
                    ↓
连接 :80 → 失败（宿主机80被Windows占用，ZLM未暴露）
```

### 修改后

```
WVP 生成:  ws://192.168.0.40:80/rtp/xxx.live.flv
                    ↓
Nginx sub_filter 改写: ws://192.168.0.40:6080/rtp/xxx.live.flv
                    ↓
浏览器拿到: ws://192.168.0.40:6080/rtp/xxx.live.flv
                    ↓
连接 :6080 → Docker映射 6080→80 → ZLM容器:80 → 播放成功
```

---

## 验证步骤

1. 修改完成后重新部署：

```bash
cd docker
docker compose down
docker compose up -d
```

2. 浏览器登录 WVP 前端，点击设备通道的"播放"按钮

3. 打开浏览器开发者工具（F12）→ Network 标签页，筛选 WS，确认 WebSocket 连接地址为 `ws://192.168.0.40:6080/rtp/...`（而非 `:80`）

4. 确认视频画面正常显示

---

## 已知限制

| 限制 | 说明 |
|------|------|
| WebRTC 仍不可用 | WebRTC 需要额外暴露 ZLM 的 UDP 8000 端口，且 ICE/STUN 协商在 bridge 网络下可能不通 |
| ZLM 管理接口暴露 | 6080 端口可直接访问 ZLM 的 `/index/api/`，建议在 ZLM 的 `config.ini` 中配置 `allow_ip_range` 限制访问 |
| sub_filter 依赖明文 JSON | 依赖修改 2 的 `Accept-Encoding ""` 生效，否则 gzip 压缩会导致端口改写静默失败 |
| 端口硬编码 | sub_filter 规则中 `:6080` 是硬编码的，如果 `.env` 中 ZLM 映射端口变更需同步修改 |
