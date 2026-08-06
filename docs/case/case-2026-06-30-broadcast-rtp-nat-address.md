# 案例：Docker NAT 导致语音广播 RTP 音频发到错误地址

> 创建时间：2026-06-30
> 影响范围：Docker 部署下，设备注册经过 NAT 时的语音喊话（Broadcast）和对讲（Talk）
> 涉及组件：`InviteRequestProcessor.java`、SIP 协议栈、Docker 网络

---

## 一、问题现象

语音喊话/对讲流程：

- ✅ 前端广播 API 返回成功，拿到 WebRTC 推流地址
- ✅ 浏览器成功将麦克风音频推送到 ZLM
- ✅ ZLM 注册 broadcast/talk 流，WVP 收到流通知
- ✅ WVP 向设备发送 SIP MESSAGE Broadcast
- ✅ 设备回复 SIP INVITE（请求接收音频）
- ✅ WVP 处理 INVITE，发送 200 OK，调用 `startSendRtp`
- ✅ ZLM 确认开始发送 RTP
- ❌ **但设备收不到音频**

## 二、日志分析

### WVP 日志：目标地址错误

```
RTP推流成功[ broadcast/... ]，172.18.0.1:49002
```

### ZLM 日志：`startSendRtp` 参数

```
dst_url=172.18.0.1         ← 错误！Docker 网关 IP
dst_port=49002
```

### 设备真实地址

设备 SIP Contact 头：
```
Contact: <sip:35020000201311005331@192.168.0.62:5061>
```

设备 LAN IP 是 `192.168.0.62`，但 ZLM 向 `172.18.0.1`（Docker 网关）发音频。

---

## 三、根因分析

### 3.1 设备注册经过 Docker NAT

设备在 `192.168.0.62` 上运行，通过 Docker 的 NAT 与 WVP 通信。WVP 在 Docker 容器内，设备 SIP 包的源 IP 被 NAT 为 Docker 网关地址 (`172.18.0.1`)。

### 3.2 WVP 使用 SDP `o=` 行地址作为 RTP 目标

在 `InviteRequestProcessor.java` 中，当设备回复 INVITE 时：

```java
// 第 549 行 - 原代码
String addressStr = sdp.getOrigin().getAddress();
```

`addressStr` 来自设备 INVITE 的 SDP `o=` 行：

```
o=35020000201311005331 0 0 IN IP4 172.18.0.1
```

设备在 SDP 中填写了它看到的自身 IP——由于 NAT，设备看到的是 Docker 网关 IP（`172.18.0.1`），而不是自己的 LAN IP（`192.168.0.62`）。

### 3.3 错误的地址传递链

```
SDP o= 行: 172.18.0.1
  → addressStr = "172.18.0.1"
    → SendRtpInfo.ip = "172.18.0.1"
      → ZLM startSendRtp dst_url = "172.18.0.1"
        → ZLM 向 172.18.0.1:49002 发 UDP 音频
          → Docker 网关收到，但没有回程路由到 192.168.0.62
            → 设备收不到！
```

---

## 四、修复方案

### 修复：使用 Via 头地址替代 SDP `o=` 地址

**文件**：`src/main/java/com/genersoft/iot/vmp/gb28181/transmit/event/request/impl/InviteRequestProcessor.java`

设备 INVITE 的 Via 头包含设备真实 LAN IP，不受 NAT 影响：

```
Via: SIP/2.0/UDP 192.168.0.62:5061;rport=55171;received=172.18.0.1
                  ───────┬───────
                   设备真实 LAN IP
```

在 `addressStr` 提取后，增加 Via 头地址覆盖：

```java
String addressStr = sdp.getOrigin().getAddress();
// 语音喊话/对讲：设备 INVITE 的 SDP o= 行可能指向 NAT 地址，
// 使用 Via 头的 host（设备真实 LAN IP），确保 RTP 音频能到达设备
try {
    ViaHeader viaHeader = (ViaHeader) request.getHeader(ViaHeader.NAME);
    if (viaHeader != null) {
        String viaHost = viaHeader.getHost();
        if (!viaHost.equals(addressStr)) {
            log.info("[语音喊话] SDP o= 行地址({})与Via头地址({})不一致，使用Via地址", addressStr, viaHost);
            addressStr = viaHost;
        }
    }
} catch (Exception e) {
    log.warn("[语音喊话] 解析Via头失败，使用SDP o=地址", e);
}
```

### 修复后效果

```
ZLM startSendRtp dst_url = "192.168.0.62"  ← 设备真实 LAN IP
ZLM 向 192.168.0.62:49002 发 UDP 音频
  → 设备直接可达 ✅
```

---

## 五、为什么 Contact 头不行

之前曾尝试从 Contact 头获取设备地址，但未成功：

```java
ContactHeader contactHeader = (ContactHeader) request.getHeader(ContactHeader.NAME);
```

原因：某些设备的 INVITE 中 Contact 头可能缺失、也可能填写 NAT 地址。而 **Via 头始终存在**，且其 host 部分由设备填写，不受 NAT 影响（NAT 只修改 `received` 参数）。

---

## 六、经验总结

1. **不要轻信 SDP `o=` 地址**：经过 NAT 的设备在 SDP `o=` 行中填写的 IP 可能是错误的（NAT 网关 IP），不可用于构建 RTP 发送目标。

2. **Via 头比 Contact 头可靠**：Via 头是 SIP 协议必选字段，其 host 部分由设备自身填写，在 NAT 场景下比 SDP `o=` 和 Contact 头都可靠。

3. **Docker NAT 的双向影响**：Docker 容器内服务的 SIP 通信（注册、INVITE 等）经过 NAT 时，设备看到的自身 IP 和 WVP 看到的设备 IP 都可能不准确。
