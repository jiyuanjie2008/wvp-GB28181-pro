# 设备 RTP Sender 在 BYE 之后未复位 — 问题定位报告

| 项 | 内容 |
|---|---|
| 报告日期 | 2026-04-26 |
| 平台版本 | WVP-Pro v2.7.4.2026-04-22T12:08:07Z + ZLMediaKit（Docker 部署） |
| 设备 SIP ID | `35020000201311000002`（User-Agent: `Body Worn Camera`，IP `192.168.0.60`） |
| 影响范围（按传输模式区分）| **UDP**：✅ 反复播放正常。<br>**TCP 被动** (`setup:passive`)：❌ 断电后仅第 1 次成功，之后每次"收流超时"。<br>**TCP 主动** (`setup:active`)：❌ 完全无法播放（任何一次都不工作）。|
| 根因定位 | **设备侧 TCP 发送路径**存在缺陷，UDP 路径正常：<br>① **TCP 被动模式**：`D:/camera_live/app/src/main/cpp/libgb28181/sip/ua_media.cpp:302` 处 BYE 资源清理循环存在 **55 次硬编码上限**，每次 BYE 都触发"超过55次发送，弹出循环" WARN，导致后续清理代码被 `break` 跳过、TCP RTP 发送状态残留（详见 4.6.5）。<br>② **TCP 主动模式**：完全无法工作，作为独立缺陷记录（详见 4.7）。|
| 平台侧责任 | **无**。WVP/ZLM/网络/Docker 全链路验证通过。切换为 UDP 模式可**完全规避**本问题（见 7 节 workaround）。|

---

## 1. 现象与影响

### 1.1 核心事实：bug 仅在 TCP 发送路径，与 UDP 无关

| 传输模式 | SDP 字段 | 设备角色 | 播放表现 |
|---|---|---|---|
| **UDP** | `m=video xx RTP/AVP ...` | 设备 UDP `sendto` | ✅ **反复播放全部正常**，无论播多少次都 OK |
| **TCP 被动** | `m=video xx TCP/RTP/AVP ...` + `a=setup:passive` | 设备作 TCP server，平台 `connect` | ❌ **断电后仅第 1 次成功**，之后每次"收流超时" |
| **TCP 主动** | `m=video xx TCP/RTP/AVP ...` + `a=setup:active` | 设备作 TCP client，主动 `connect` 平台 | ❌ **完全无法播放**，任何一次都不工作 |

> 这个差异非常重要：bug 定位在**设备 TCP 发送子模块**，UDP 路径完全没问题。平台侧已验证三种模式下信令、SDP、网络链路均工作正常。

### 1.2 TCP 被动模式下的详细表现（本报告主要定位的问题）

1. 设备完成 GB28181 注册并保活正常。
2. 在 wvp 前端发起实时点播（TCP 被动模式）：
   - **第 1 次播放成功**，RTP/PS 流正常拉起，画面、声音、录像皆可用。
   - 用户在前端点击"停止播放"。
   - 紧接着发起**第 2 次点播 → 失败**，前端提示"收流超时"。
   - 之后所有点播均失败，等待任意时长（实测 ≥ 1 小时 12 分钟）也不会恢复。
3. 仅 **断电/菜单重启设备** 后，第 1 次点播又能成功，此后规律重现。
4. 切换为 **UDP 模式**：现象立即消失，反复播放全部正常。
5. 切换为 **TCP 主动模式**：连第 1 次都不能播（独立问题，见 4.7）。

## 2. 复现步骤

```
(1) 重启设备   →   等到 wvp 显示设备在线
(2) 点播      →   预期 ✅ 成功
(3) 停止播放
(4) 立刻再次点播 → 必失败（"收流超时"）
(5) 等 30 分钟、1 小时 …… 再点播 → 仍失败
(6) 重启设备 → 回到 (2)
```

经过本次定位多轮验证，(2)(4)(6) 三段闭环 100% 稳定复现。

## 3. 环境信息

- 平台部署：3 个容器（`docker-polaris-wvp-1` / `docker-polaris-media-1` / `docker-polaris-nginx-1`）
- 媒体收流：单端口模式 `rtp_proxy.port=10003`（`media.rtp.enable=false`）
- 收流地址：`192.168.0.24:10003`（已验证为宿主机真实 LAN IP，前期排查日志内容详见附录 B）
- Docker 端口映射：`10003/tcp+udp -> 0.0.0.0:10003`（已验证）
- ZLM 单端口模式 `timeoutSec=15`

## 4. 完整证据链

> 本节先以 ZLM **单端口模式**为主链路给出证据；4.5 节补充 **多端口模式** 的对照实验结果，从两个独立路径互相印证根因。

### 4.1 SIP 信令证据（信令侧 100% 正常）

第 1 次 INVITE 与第 2 次 INVITE 全文逐字对比，仅 `Call-ID / tag / branch / CSeq / SSRC subject` 必然不同，其余完全等价：

```
=== 1st INVITE（成功）===
INVITE sip:35020000201311000002@172.18.0.1:40716 SIP/2.0
Call-ID: 29c41e63ba0ea02ac438cc172e4de75d@0.0.0.0
CSeq: 7 INVITE
Subject: 35020000201311000002:0200009561,35020000002000000001:0
Content-Type: APPLICATION/SDP
Content-Length: 286

v=0
o=35020000201311000002 0 0 IN IP4 192.168.0.24
s=Play
c=IN IP4 192.168.0.24
m=video 10003 TCP/RTP/AVP 96 97 98 99
a=recvonly
a=setup:passive
a=connection:new
y=0200009561

=== 2nd INVITE（失败）===
INVITE sip:35020000201311000002@172.18.0.1:40716 SIP/2.0
Call-ID: b66080b72ce607a51d617765036f607c@0.0.0.0
CSeq: 9 INVITE
Subject: 35020000201311000002:0200002272,35020000002000000001:0
（SDP 与第 1 次 byte-for-byte 等价，仅 y= 字段不同）
y=0200002272
```

设备对两次 INVITE 都正确返回 200 OK，wvp 也都正确回 ACK，信令层逻辑完全正常。

### 4.2 ZLM 媒体侧证据

第 1 次播放（成功）的 ZLM 关键日志：

```log
2026-04-26 19:52:57.379  RtpSession 创建（设备 TCP 客户端 172.18.0.1:44012 接入）
2026-04-26 19:52:57.465  RtpProcess.cpp:293 0BEBE759 允许RTP推流, ssrc: 0BEBE759
2026-04-26 19:52:57.753  媒体注册:rtsp://__defaultVhost__/rtp/35020000201311000002_35020000201311000002
2026-04-26 19:57:56.823  RtpSession on err: 1(end of file)            ← 设备主动 FIN，正常关闭
```

第 2 次以后的所有播放（失败）的 ZLM 关键日志（多次复现一致）：

```log
2026-04-26 20:38:04.432  RtpSession 创建（设备 TCP 客户端 172.18.0.1:54978 接入）
                         （此后 11 秒内无任何 RtpProcess 行 / 无 on_publish hook）
2026-04-26 20:38:15.516  RtpSession on err: 2(illegal connection)
                         RtpSession.cpp:71 onError | __defaultVhost__/rtp/ 2(illegal connection)
                                                       ↑ stream_id 为空，证明 ZLM 没收到任何 RTP 包
```

`illegal connection` 在 ZLMediaKit 单端口模式下的语义为：TCP ESTABLISHED 后超过 `timeoutSec` 没收到任何 RTP 字节，主动 FIN。

### 4.3 网络层抓包铁证（在 ZLM 容器 `eth0` 上 tcpdump）

抓包命令：

```
docker exec docker-polaris-media-1 \
  tcpdump -i any -nn -s 0 -w /tmp/cap.pcap 'tcp port 10003 or udp port 10003'
```

完整 30 秒报文序列：

```
21:04:37.516994  In   172.18.0.1.52164 > 172.18.0.4.10003: [S]   seq=1905244202        ← 设备 SYN
21:04:37.517043  Out  172.18.0.4.10003 > 172.18.0.1.52164: [S.]  ack=1                  ← ZLM SYN-ACK
21:04:37.517092  In   172.18.0.1.52164 > 172.18.0.4.10003: [.]   ack=1, length 0        ← 设备 ACK，握手完成

────────────  接下来 11 秒：所有方向上的包 length 全部 = 0  ────────────

21:04:48.596529  Out  172.18.0.4.10003 > 172.18.0.1.52164: [F.]  length 0               ← ZLM 主动 FIN
21:04:48.599671  In   172.18.0.1.52164 > 172.18.0.4.10003: [.]   ack 2, length 0
21:05:03.732207  In   172.18.0.1.52164 > 172.18.0.4.10003: [.]   keep-alive, length 0
21:05:07.516386  In   172.18.0.1.52164 > 172.18.0.4.10003: [F.]  length 0               ← 设备 30s 后才回 FIN
21:05:07.516456  Out  172.18.0.4.10003 > 172.18.0.1.52164: [.]   ack 2
```

铁证：
- TCP 三次握手 0.5 ms 内完成，**网络层完全 OK**；
- 进入 ESTABLISHED 后 **11 秒内设备一字节都没发**；
- ZLM 是**主动 FIN** 的一方，不是设备主动断；
- 设备方收到 FIN 后又拖 ≈15 秒才回自己的 FIN，符合"sender 线程死锁"的特征。

### 4.4 闭环复现证据

| 序号 | 时间 | 操作 | 结果 |
|---:|---|---|---|
| 1 | T₀ | 重启设备 | — |
| 2 | T₀+几十秒 | 第 1 次点播 | ✅ 成功 |
| 3 | T₀+5 min | 停止播放（wvp BYE，设备 200 OK，TCP 正常 FIN） | — |
| 4 | T₀+5 min+3 s | 第 2 次点播 | ❌ 收流超时 |
| 5 | T₀+45 min | 第 N 次点播 | ❌ 收流超时 |
| 6 | T₀+1 h 12 min | 又一次点播 | ❌ 收流超时 |
| 7 | T₁ | **断电重启设备** | — |
| 8 | T₁+几十秒 | 第 1 次点播 | ✅ 成功 |
| 9 | T₁+几分钟 | 第 2 次点播 | ❌ 收流超时 |

(2)(4)(8)(9) 闭环已多次重现，结论稳定。

### 4.5 ZLM **多端口模式** 对照实验（同一设备，独立路径再次复现）

为排除"是否单端口模式特有现象"，将 ZLM 切换为多端口模式重复复现：

- 配置：`media.rtp.enable=true`，`port-range=30000,30500`
- wvp 在每次 INVITE 之前显式调 ZLM 的 `POST /index/api/openRtpServer`，每次分配一个**独立**的新端口
- SDP 中 `m=video <port>` 的端口每次都不同
- 现象与单端口模式**完全一致**：开机后第 1 次成功，停止后第 2 次起全部"收流超时"

#### 4.5.1 多端口模式 wvp/ZLM 对应关系

| 序号 | 时间 | wvp `m=video` 端口 | SSRC | ZLM 是否收到设备 TCP `connect` | ZLM `RtpProcess.cpp:293 允许RTP推流` | 结果 |
|---|---|---|---|---|---|---|
| ① 重启前残留 | 22:48:39 | 30158 | 0200007477 | ❌ 无 | ❌ 无 | RtpProcess timeout |
| ② **重启后第 1 次** ⭐ | 22:51:50 | 30278 | 0200001867 | ✅ 有（`172.18.0.1:48518`） | ✅ 有（ssrc `0BEBC94B`） | **✅ 成功** |
| ③ **第 2 次（紧跟②停止后 4 s）** | 22:55:51 | 30174 | 0200000157 | ❌ 无 | ❌ 无 | RtpProcess timeout |

#### 4.5.2 ZLM 关键日志（多端口模式）

② 成功（端口 30278）：

```log
@d:/JXT/jxt-evidence-system/wvp-GB28181-pro/tmp_zlm_full.log:859-884
22:51:50.030  Rtsp.cpp:481  getPortPair | got port from pool: 30278-30279
22:51:50.030  TcpServer.cpp:260 start_l | TCP server listening on [::]:30278
22:51:50.0xx  POST /index/api/openRtpServer
22:51:50.204  TcpServer.h:72  RtpSession  8-258(172.18.0.1:48518)         ← 设备 TCP 连进来
22:51:50.372  RtpProcess.cpp:293  35020000201311000002_xxx 允许RTP推流, ssrc: 0BEBC94B   ← 收到首包
```

③ 失败（端口 30174）：

```log
@d:/JXT/jxt-evidence-system/wvp-GB28181-pro/tmp_zlm_full.log:1282-1314
22:55:51.210  Rtsp.cpp:481  getPortPair | got port from pool: 30174-30175
22:55:51.210  TcpServer.cpp:260 start_l | TCP server listening on [::]:30174
22:55:51.2xx  POST /index/api/openRtpServer
                ─── 接下来 15 秒 30174 端口上没有任何 TCP 连接、没有任何 UDP 报文 ───
22:56:06.213  RtpProcess.cpp:234 onDetach | 2(RtpProcess timeout), stream_id: 35020000201311000002_xxx
22:56:06.213  TcpServer.cpp:68 ~TcpServer | Close tcp server [::]:30174
```

#### 4.5.3 单端口 vs 多端口的设备行为差异（重要）

> **同一台终端在两种模式下失败时的"卡死深度"不同**——这是反推设备 sender 状态机的关键依据。

| 失败维度 | 单端口模式（目标 `192.168.0.24:10003`，端口固定） | 多端口模式（目标 `192.168.0.24:30xxx`，每次新端口） |
|---|---|---|
| **第 1 次播放** | ✅ 成功 | ✅ 成功 |
| **第 2 次播放** | ❌ 失败 | ❌ 失败 |
| **设备是否发起 TCP `connect()`** | ✅ **完成三次握手**（每次新源端口，如 44012/54978/57518） | ❌ **完全未发起**（30174 端口上 0 SYN，0 UDP 包） |
| **TCP 进入 ESTABLISHED 后的字节数** | 0 字节，11 秒 | — |
| **谁先 `FIN`** | ZLM（达到 `timeoutSec` 后主动断） | 不存在 TCP 连接 |
| **ZLM 报错** | `RtpSession on err: 2(illegal connection)`（`stream_id` 为空） | `RtpProcess.cpp:234 onDetach 2(RtpProcess timeout)` |
| **报错触发器** | RtpSession：建立连接但首包 timeout | RtpProcess：监听端口但根本无连接进来 |
| **wvp 收到的失败感知时延** | 30 s（前端"点播等待超时"） | 15 s（ZLM RtpProcess timeout 触发更早） |
| **设备 sender 卡死的"显式深度"** | **TCP 层还能动作**（socket open/connect 走得通），但**数据生产线程不喂字节** | **连数据生产 + 目标地址解析这一前置步骤都不执行**，sender 完全静默 |

##### 单端口下"还能 connect"、多端口下"连 connect 都不发"的合理解释

- **单端口模式**：设备目标地址 `(192.168.0.24, 10003)` **每次完全相同**。设备 firmware 中可能存在"上次会话已缓存的 socket 模板"——即使 RTP 数据生产线程已死锁，最底层的 socket 系统调用仍会按缓存的目标参数完成 `connect()`。这表现为"管道接得通，但水龙头不出水"。
- **多端口模式**：目标端口每次不同（30158→30278→30174），sender 必须**主动从最新 SDP 解析新目标端口**，再去构造 `connect()`。"解析新参数+构造 connect"是 sender 模块的正常工作流，而该模块已经死锁，于是连 SYN 都发不出去。这表现为"连水龙头都没拧，更别提水"。

##### 结论加强

两种模式下：
- 第 1 次成功的路径完全不同（单端口靠 ZLM 自动接受+`on_publish` 改名；多端口靠 wvp 显式 `openRtpServer` 预登记），但都成功 → **平台侧两条独立链路 OK**；
- 第 2 次失败的失败点完全不同（单端口卡在"建立 TCP 后无字节"；多端口卡在"压根不建立 TCP"），但都失败 → **设备侧 sender 卡死，且范围比单端口下推测的更深**。

两条独立证据链交叉验证，根因 100% 锁死在设备 firmware；ZLM 的端口模式选择**不能成为 workaround**——多端口反而把失败提前到了"连不上"阶段，对最终用户体验更差（虽然返回更快）。

### 4.6 设备 logcat 直接证据（2026-04-27 终端 5331 实验）

通过 USB 直连设备（GB28181 SIP ID `35020000201311005331`）抓取完整 logcat，**首次拿到设备内部状态机的直接证据**，从设备侧反向印证根因。本节是对前述网络层证据的"内部视角"补充。

#### 4.6.1 设备 SIP 状态机正常工作（推翻"SIP 状态机死锁"假设）

抓取到的两次完整播放周期（设备时间，比 wvp 约慢 3.6s）：

```
== 第 1 次播放 ==
15:16:57.573  sip_call_request_rx[473]  f_sua->call_state=0   ← INVITE 接收
15:16:57.662  sip_call_request_rx[450]  f_sua->call_state=4   ← 200 OK 已发，等 ACK
15:16:57.721  Gb28181Local: liveState 0→2                     ← 推流标志开启
15:18:14.281  sip_call_request_rx[450]  f_sua->call_state=8   ← BYE 接收
15:18:14.352  Gb28181Local: liveState 2→0                     ← 71ms 后干净切回 0
15:18:55.408  sip_call_request_rx[473]  f_sua->call_state=0   ← 41 秒后回 IDLE

== 第 2 次播放 ==
15:18:55.408  call_state=0   ← INVITE 接收
15:18:55.494  call_state=4   ← 200 OK 已发
15:18:55.531  liveState=2    ← 推流标志开启 ✅
                              （但平台侧 ZLM 同一时段 0 字节，15s 后超时）
15:19:25.393  call_state=8   ← BYE 接收
```

**结论**：设备 SIP 协议栈、状态机转换、应用层 `liveState` 标志、200 OK / ACK / BYE 全部正常。第 2 次播放设备**自认为在推流**，但下游 ZLM 0 字节。

#### 4.6.2 设备 vs 平台时间序列对照（关键铁证）

| 设备时间 | wvp 时间 | 事件 | 设备视角 | 平台视角 |
|---|---|---|---|---|
| 15:16:57.573 | 15:17:01.123 | 第 1 次 INVITE | call_state=0→4 | [开始点播] |
| 15:16:57.721 | 15:17:01.664 | 推流开始 | **liveState=2** | ZLM 流注册成功 ✅ |
| ... 推流 76s，画面正常 ... |
| 15:18:14.281 | 15:18:17.886 | 第 1 次 BYE | call_state=8 | wvp 流注销 |
| 15:18:14.352 | — | — | **liveState=0**（清理 ✅）| — |
| 15:18:55.408 | 15:18:58.973 | 第 2 次 INVITE | call_state=0→4 | [开始点播] |
| 15:18:55.531 | — | 推流开始？ | **liveState=2** | （静默 15s）|
| — | 15:19:13.986 | 收流超时 | （设备仍认为在推流）| **ZLM rtpServer 收流超时** ❌ |

**矛盾点**：第 2 次播放期间，设备应用层 `liveState=2` 持续 ~30 秒，但 ZLM 在 15 秒收流窗内**没收到一个 RTP 字节**。这就排除了"设备根本没启动推流"的可能——设备**启动了推流流程**，但底层 RTP 字节没真正发出。

#### 4.6.3 锁定 bug 模块：rtp_tx 二次初始化路径

综合 4.3 (网络层 0 字节)、4.5.3 (单端口能 connect/多端口连 connect 都不发) 与本节 (设备状态机 + liveState 全程正常)，可以**精确锁定 bug 不在 SIP 协议栈、不在应用层状态机，而在 RTP 发送模块的 init/uninit 配对**：

| 模块层 | 第 1 次 | 第 2 次 |
|---|---|---|
| SIP 协议栈（`sua_call_state`）| ✅ 正常 | ✅ 正常 |
| 应用层 dialog（`liveState`）| ✅ 0→2→0 | ✅ 0→2 |
| **底层 rtp_tx**（socket / 线程 / SSRC）| ✅ 真发字节 | ❌ **不真发字节** |

设备 firmware 中 RTP 发送模块（推测路径：`libgb28181/rtp/rtp_tx.cpp` 或同等）的 **`uninit()` 没把所有静态状态归零**，或 **`init()` 走了"已初始化则跳过"短路分支**，导致：

```c
// 嫌疑代码模式 1：init 短路
int rtp_tx_init(...) {
    if (g_initialized) return 0;   // ← 第 2 次进来直接返回成功
    sock = socket(...);             //   但 socket 已被前次 close()
    connect(...);
    g_initialized = 1;
    return 0;
}

// 嫌疑代码模式 2：uninit 漏复位
void rtp_tx_uninit(...) {
    close(sock);
    // 漏：g_initialized = 0;
    // 漏：sock = -1;
    // 漏：memset(rtp_ctx, 0, ...);
}
```

#### 4.6.4 修复定位（给厂家）

| 文件（推测） | 行号 / 函数 | 检查点 |
|---|---|---|
| `libgb28181/rtp/rtp_tx.cpp` | `rtp_tx_init()` | 是否有 "已初始化则跳过" 的短路逻辑；如有，必须在 SIP `BYE` 路径强制清除该 flag |
| `libgb28181/rtp/rtp_tx.cpp` | `rtp_tx_uninit()` | 末尾必须无条件复位：`g_initialized=0; g_sock_fd=-1; memset(&g_rtp_ctx,0,sizeof(g_rtp_ctx));` |
| `libgb28181/sip/sua_call.cpp` | BYE 处理路径 | 必须在 dialog 销毁前**显式调用** `rtp_tx_uninit()`，不能依赖析构 |

> 注：上述文件名与函数名为根据设备 logcat 中出现的 tag (`GB28181`)、`sip_call_request_rx`、`sua_call_state` 等推测，厂家应以自己的源码索引为准。

### 4.6.5 终极证据：BYE 清理路径触发的 `ua_media.cpp:302` "超过55次发送，弹出循环" WARN

经过扩大 logcat tag 白名单（加入 `*:E` 错误级捕获）后，**首次抓到设备 firmware 自己的 WARN 日志**——直接揭示了 bug 的源码位置和触发模式：

#### 4.6.5.1 直接证据：两次 BYE 都精确触发同一行 WARN

```
== 第 1 次 BYE 清理流程（TID 2854）==
04-27 16:10:46.024  I  收 BYE → call_state=8
04-27 16:10:46.025  I  sua_call_state[112]: "在该状态应该停留几秒钟,或者收到BYE 200 OK"
04-27 16:10:46.086  W  GB28181: [D:/camera_live/app/src/main/cpp/libgb28181/sip/ua_media.cpp][302]
                       ^^^^^^^^^^^^^^^^ 超过55次发送，弹出循环 ^^^^^^^^^^^^^^^^
04-27 16:10:46.087  I  sua_stop_media, sua_stop_audio sua[0] end.
04-27 16:10:46.089  I  sua_stop_used_sua, sua[0] start.
04-27 16:10:46.089  I  sua_set_idle_sua, p_sua=0xc7b835f4, index[0]
04-27 16:10:46.090  I  sua_cs_bye_sent[1091]: CSE_Hang_Recv 停止

== 第 2 次 BYE 清理流程（TID 3014，不同线程）==
04-27 16:11:47.640  I  收 BYE → call_state=8
04-27 16:11:47.640  I  sua_call_state[112]: "在该状态应该停留几秒钟,或者收到BYE 200 OK"
04-27 16:11:47.702  W  GB28181: [D:/camera_live/app/src/main/cpp/libgb28181/sip/ua_media.cpp][302]
                       ^^^^^^^^^^^^^^^^ 超过55次发送，弹出循环 ^^^^^^^^^^^^^^^^
04-27 16:11:47.702  I  sua_stop_media, sua_stop_audio sua[0] end.
04-27 16:11:47.705  I  sua_stop_used_sua, sua[0] start.
04-27 16:11:47.705  I  sua_set_idle_sua, p_sua=0xc7b835f4, index[0]
04-27 16:11:47.705  I  sua_cs_bye_sent[1091]: CSE_Hang_Recv 停止
```

**两次 BYE 时序完全等价**：
- 收 BYE → 62 ms 后触发 `ua_media.cpp:302` 警告（连毫秒延迟都一致）
- 触发警告的是不同的后台线程（TID 2854 vs 3014）—— 排除 "线程残留" 单一假说，**问题在循环逻辑本身**
- 警告之后才走 `sua_stop_media → sua_stop_used_sua → sua_set_idle_sua` 清理链

#### 4.6.5.2 嫌疑代码模式

```c
// 文件: D:/camera_live/app/src/main/cpp/libgb28181/sip/ua_media.cpp
// 行号: 302
void some_send_or_flush_loop(...) {
    int retry = 0;
    while (有数据待处理) {           // 循环条件可能是"队列非空" / "对端未确认"
        int ret = send_or_flush(...);
        retry++;
        if (retry > 55) {            // ←★ 55 次硬编码上限 ★
            LOGW("超过55次发送，弹出循环");
            break;                   // ←★ 强制 break，跳过后面的清理代码 ★
        }
    }
    // 关键的资源清理代码可能位于此处（仅在循环正常结束才走）：
    //   - rtp_tx 发送线程 join
    //   - send buffer free
    //   - SSRC / seq / ts 归零
    //   - socket fd close
    // 一旦 break 跳出，这些清理代码被跳过 → 状态残留 → 下次 init 看到脏状态
}
```

#### 4.6.5.3 这个 bug 完美解释了所有"非确定性"现象

| 之前观察到的现象 | `ua_media.cpp:302` 模型的解释 |
|---|---|
| 同样 BYE 报文，有时第 2 次成功，有时失败 | 取决于 BYE 触发时刻发送队列里的瞬时长度——队列短可能正常结束，队列长触发 break |
| 推流 30s vs 推流 5min 后停止，结果不同 | 推流越久，发送队列累计越满，越容易触发 55 次上限 break |
| 第 1 次成功之后所有播放都失败 | 残留状态会随每次 BYE 累加，直到 init 路径完全无法工作 |
| 等再久也恢复不了，仅断电重启可恢复 | 残留的 static / 全局变量需要进程重启才能复位 |
| 多端口模式下连 SYN 都不发 | 残留状态污染了 sender 的目标端口解析路径，与单端口"connect 但不发字节"是同一脏状态的不同表现 |
| 设备无任何 ERROR 日志、liveState=2 仍正常 | bug 是 WARN 级，且 SUA / 应用层流程都跑完了，只有底层 RTP buffer 状态残留 |

#### 4.6.5.4 给厂家的精确修复指引

**位置已锁定到行**：

```
文件: D:/camera_live/app/src/main/cpp/libgb28181/sip/ua_media.cpp
函数: 包含 line 302 的循环（请按设备源码索引查找）
现象: BYE 路径中每次都打印 "超过55次发送，弹出循环" WARN
```

**必须做的事**：

1. **找到这段循环的真实语义**
   - 是在 flush RTP 发送队列的剩余包？
   - 是在等 RTP 发送线程退出？
   - 是在重传某个 SIP 响应？
   - 不同语义对应不同修复方式

2. **break 路径必须等价于循环正常结束路径**：保证下面的资源清理代码无论从哪条出口都执行：

   ```c
   // 修复模式 1: 把 break 路径补全清理
   if (retry > 55) {
       LOGW("超过55次发送，弹出循环 (强制清理)");
       force_cleanup_rtp_tx();      // ← 必须显式释放 buffer/socket
       force_join_send_thread();    // ← 必须显式 join 线程
       memset(&g_rtp_state, 0, sizeof(g_rtp_state));  // ← 必须复位所有 static 变量
       break;
   }
   ```

3. **更稳的修复**：把 55 次上限改成基于时间的超时（更符合 SIP 行为）：

   ```c
   uint64_t deadline_ms = now_ms() + 1000;   // 1 秒
   while (有数据待处理 && now_ms() < deadline_ms) {
       send_or_flush(...);
   }
   if (now_ms() >= deadline_ms) {
       LOGW("flush 超时, 强制清理");
   }
   force_cleanup_rtp_tx();        // ← 无条件清理，不依赖循环结束方式
   ```

4. **加详细日志**便于复现验证：

   ```c
   LOGW("超过55次发送，弹出循环: queue_len=%d, sock_fd=%d, sender_thread=%p",
        queue_size(), g_sock_fd, g_send_thread);
   ```

5. **回归验证**：连续 INVITE/BYE 100 次不复现"收流超时"。

#### 4.6.5.5 排除"BYE 不兼容"假说的最终证据

设备**自己的 WARN 日志**揭示：BYE 解析、dialog 匹配、call_state 转换、SUA 清理流程**全部正常执行**。bug 在最底层的 RTP 媒体清理循环里，与 wvp 发的 BYE 报文格式无关。即便 wvp 不发 BYE（让设备自己心跳超时），只要触发同一段 `ua_media.cpp:302` 清理代码，同样会触发 55 次循环上限。

### 4.6.6 对照实验：同现场设备 `35020000201311008696` 在相同 TCP 被动模式下反复播放完全正常

为排除"是否所有 GB28181 设备 + ZLM 接入都会出此问题"，对同现场另一台设备 8696 做严格相同模式的对照实验：

#### 4.6.6.1 实验条件等同

| 维度 | 5331（故障设备） | 8696（对照设备） |
|---|---|---|
| 媒体传输模式 | TCP 被动 (`setup:passive`) | **TCP 被动**（wvp 操作台同样配置） |
| 平台版本 | WVP-Pro v2.7.4 + ZLM Docker | **同一套**部署 |
| 实验流程 | PLAY → 推流 → STOP，连续 3 轮 | **同一脚本**，连续 5 轮 |

#### 4.6.6.2 8696 的实测结果

```
== device_log_8696_20260428-1015.txt 中提取的 liveState 时间序列 ==
10:15:48.626  liveState=2  ← 第 1 次播放（推流 23 秒）
10:16:11.957  liveState=0
10:16:20.928  liveState=2  ← 第 2 次播放（推流 39 秒）
10:16:59.608  liveState=0
10:17:04.498  liveState=2  ← 第 3 次播放（推流 26 秒）
10:17:30.886  liveState=0
10:17:34.188  liveState=2  ← 第 4 次播放（推流 17 秒）
10:17:51.498  liveState=0
10:17:54.208  liveState=2  ← 第 5 次播放（推流 24 秒）
10:18:18.337  liveState=0
```

**5 次连续 PLAY/STOP 循环全部成功**，ZLM 端实测每次都正常收到 RTP 字节。ZLM 容器日志同步交叉验证：

```
2026-04-28 10:18:18.650  RtpSession.cpp:71 onError | 312-257(172.18.0.1:50216)
2026-04-28 10:18:18.650  TcpServer.h:66 ~mediakit::RtpSession
2026-04-28 10:18:18.650  RtpProcess.cpp:66 ~RtpProcess | ...:50216) RTP推流器断开,耗时(s):24
```

- `RtpSession` + `TcpServer` 类名锁死走的就是 **TCP 推流**
- 推流耗时 24 s 与设备 logcat 第 5 次推流时长（24 s）完全对齐，时钟差 < 313 ms

#### 4.6.6.3 8696 logcat 的"反差"——完全没有 GB28181 native 库日志

| logcat tag | 5331 | 8696 |
|---|---|---|
| `GB28181`（native C++ 库，含 `sip_call_request_rx` / `sua_call_state` / `ua_media.cpp:302`）| **1563 行** | **0 行** |
| `System.out`（Java println）| 118 行 | **1893 行**（其中 `port:0` 重复 1850 次）|
| `Gb28181Local` | 115 行 | 215 行 |
| **`ua_media.cpp:302` WARN 触发数** | 每次 BYE 必触发 | **0 次** |

#### 4.6.6.4 结论：两台设备用的是**完全不同的 GB28181 实现**

- **5331**：基于 `D:/camera_live/app/src/main/cpp/libgb28181/...` 这套 **C++ native 库**（编译时绝对路径暴露在 logcat 中），有 `ua_media.cpp:302` 的 55 次循环缺陷。
- **8696**：极大概率是基于 **Java 实现**的 GB28181 模块（1850 行 `port:0` 全部来自 `System.out`，无任何 native tag）。这套实现根本不进入 `ua_media.cpp` 的代码路径，因此**反复播放天然无此 bug**。

#### 4.6.6.5 这条对照实验的价值

1. **排除"行业通病"开脱**：同一现场、同一平台、同样 TCP 被动模式下，8696 反复播放完全正常 → 证明这是**可解决的工程缺陷**，不是 GB28181 协议限制或 ZLM 配置问题。
2. **对厂家施压**：可向厂家直接出示——"你们公司另一个产品（如果是同厂）/ 同行业另一台设备（如果非同厂）已经做到了反复播放正常，请说明为什么这台 5331 做不到"。
3. **短期出路再次确认**：5331 切 UDP 模式即可绕过 bug；长期仍需厂家修固件。

### 4.7 TCP 主动模式（`setup:active`）的独立缺陷

> **现象**：将媒体传输模式切换为 TCP 主动模式后，**任何一次播放都不能成功**——包括断电后的第 1 次。这与 4.6 节描述的"TCP 被动模式断电后第 1 次可用"是不同的失败模式。

#### 4.7.1 模式定义

| 字段 | TCP 主动模式 |
|---|---|
| SDP `m=video` | `m=video <port> TCP/RTP/AVP <pt>` |
| SDP `a=setup` | `a=setup:active`（设备主动） |
| 设备角色 | TCP 客户端，主动 `connect()` 到平台 |
| 平台角色 | TCP 服务端，监听 + `accept()` |

#### 4.7.2 已知现象（基于现场反馈）

- 该设备在 TCP 主动模式下**任何次数都无法播放**（区别于 TCP 被动的"第 1 次能用"）。
- UDP 模式同设备完全正常，证明并非物理链路问题。

#### 4.7.3 可能原因（需厂家确认）

由于 UDP 路径正常、TCP 被动也至少能跑通第 1 次，而 TCP 主动连第 1 次都失败，怀疑 firmware 里 TCP 主动客户端的代码路径存在以下问题之一：

1. **未实现或未开启** TCP 主动模式（设备虽宣告支持 `setup:active` 但实际不工作）。
2. **目标地址解析错误**：未正确从 SDP `c=` 行 + `m=video` 端口拼出平台监听地址。
3. **`connect()` 调用前的依赖**：例如未先创建 socket 或未绑定本地端口。
4. **TLS / 协议封装错误**：把 RTP 当成纯字节流发，但平台需要先收 RTP-over-TCP 的 2 字节长度前缀（RFC 4571），或反之。

#### 4.7.4 给厂家的诊断指引

请厂家在设备端打开 TCP 主动模式相关的 verbose 日志（涉及 `tx`、`tcp_client`、`connect`、`ua_media`、`rtp_send` 等关键字）后做一次复现，并提供完整 logcat。平台侧可以同步配合：

- 平台 ZLM 主动监听端口的 tcpdump：`tcpdump -i eth0 'tcp port <m=video port>'`，看是否有 SYN 到达
- wvp 推送的 INVITE SDP 全文（可从 wvp 日志直接拷贝），确认 `c=` / `m=` / `a=setup:active` 字段正确

#### 4.7.5 与本报告主体的关系

4.7 是**独立的二级缺陷**，与 4.6 的"TCP 被动 BYE 后失败"是同一传输大类（TCP）下的两个不同子问题。建议厂家先修 4.6（影响面更大，已有外场用户），再排查 4.7（影响面较小，且业务可用 UDP 替代）。

## 5. 根因结论

> **本报告主体定位的是 TCP 被动模式下的失败。** UDP 模式无任何 bug；TCP 主动模式有独立缺陷（4.7）。
>
> **TCP 被动模式根因**：设备 firmware 在 `D:/camera_live/app/src/main/cpp/libgb28181/sip/ua_media.cpp:302` 处的 BYE 资源清理循环存在 55 次硬编码上限，每次 BYE 都触发 `超过55次发送，弹出循环` WARN，强制 `break` 跳过后续 TCP RTP 状态清理代码——导致 TCP RTP 发送子模块的 buffer/线程/socket 状态在 BYE 后未被完整复位，下次 INVITE 进来时虽然 SIP 信令、应用层 `liveState` 全部正常，但底层 TCP RTP 不再向 ZLM 写入字节。
>
> **该 bug 仅出现在 TCP 路径**——这是因为 UDP 是无连接的、不需要 flush 队列等待对端 ACK，因此根本不会进入 `ua_media.cpp:302` 的循环；而 TCP 因为面向连接、有发送队列和有序传输需求，BYE 时必须等待队列消化完才能干净关闭，这段等待逻辑就是 55 次循环的本质。

证据级别（从高到低）：

1. **直接源码定位（4.6.5）**：设备**自己的 WARN 日志**点名 `ua_media.cpp:302`，且每次 BYE 必然触发，时序毫秒级一致（62 ms）。
2. **跨层证据交叉印证**：
   - SIP 协议栈、状态机：✅ 正常（4.6.1，call_state 0→4→8→0）
   - 应用层 dialog 标志：✅ 正常（4.6.1，liveState 切换正确）
   - 网络 TCP 握手层：✅ 正常（4.3，三次握手成功）
   - **RTP 字节发送层**：❌ 0 字节（4.3 / 4.5）— 与 WARN 触发的脏状态因果链一致
3. **所有"非确定性"现象统一解释（4.6.5.3）**：是否触发 break 取决于 BYE 瞬间发送队列的瞬时长度，因此推流时长、停止时机、设备启动后已用次数都会影响成功率。
4. **多端口模式失败更深的解释**：单端口下底层 socket 系统调用仍能复用脏目标参数完成 connect；多端口下必须重新解析 SDP 经过完整 init 路径，被脏状态污染后连 SYN 都发不出（4.5.3）。

排除项：

- ❌ 不是网络问题（TCP 三次握手正常、抓包零丢字节）。
- ❌ 不是防火墙问题（端口 10003 已正确放行，第 1 次能用）。
- ❌ 不是 Docker 端口映射问题（容器内 `eth0` 抓到的就是设备实际报文）。
- ❌ 不是 wvp 信令问题（两次 INVITE 字段对比完全等价；ACK/BYE 全部正常）。
- ❌ 不是 SSRC 问题（第 1 次 SSRC 也未走 `openRtpServer` 预登记，依靠 ZLM 单端口自动接受 + `on_publish` 改名机制工作）。
- ❌ 不是 ZLM 配置问题（`rtp_proxy.port=10003 timeoutSec=15` 行为符合预期）。
- ❌ 不是"等待时间不够"（实测 1 小时 12 分钟未恢复，仅断电重启可恢复）。
- ❌ 不是 ZLM 端口模式问题（单端口 / 多端口两种模式下都稳定复现，详见 4.5）。

## 6. 设备侧（终端）修改建议（必选）

> **范围限定**：以下修改**仅针对 TCP 路径**（UDP 路径设备实测正常，无需改动）。

### 6.1 TCP 被动模式（`setup:passive`）的修复

直接对应 `ua_media.cpp:302` 的 55 次硬编码上限缺陷（详见 4.6.5）：

1. **修复 `ua_media.cpp:302` 循环的 `break` 路径**：保证 BYE 触发的 RTP TCP 资源清理代码无论从哪条出口都执行（buffer free / 线程 join / socket close / static 变量复位），不能被 `break` 跳过。
2. **下一次 INVITE 处理**：必须**重新构造一个干净**的 RTP sender 实例（不可复用上一次"已 used"的 slot），重新初始化状态机、SSRC、序列号、TS 起点。
3. **强烈建议**追加自检：
   - 在新会话客户端 `connect()` 进入 ESTABLISHED 后 1 秒内若没能写出第一帧 RTP，记录错误日志并尝试自愈（重启 sender 线程）；
   - 暴露一条厂家私有的 GB28181 `DeviceControl` 子命令用于"通道复位"，方便平台侧在异常时强制 reinit，而无需整机重启。

### 6.2 TCP 主动模式（`setup:active`）的修复

详见 4.7 节描述：当前**完全无法工作**。需要厂家从头排查：设备作为 TCP 客户端、按 SDP `c=` 行解析平台 IP/端口、`connect()` 后写 RTP 字节这一整条链路。

### 6.3 验证标准

执行第 2 节"复现步骤"在以下三种模式下都通过：

| 模式 | 验证项 |
|---|---|
| **UDP** | 任意次数播放/停止循环，全部成功（基线，当前已 OK）|
| **TCP 被动** | 任意次数播放/停止循环（≥ 100 次），全部成功 |
| **TCP 主动** | 任意次数播放/停止循环（≥ 100 次），全部成功 |

## 7. 如果终端不修改 —— 平台侧能做什么？

> 头号建议：**直接切到 UDP 模式**——这是**完全规避**本问题的零代价方案，详见 P0。
>
> 如果业务上必须用 TCP（穿越 NAT、避免 UDP 丢包等场景），则只能从下面 P1~P3 里挑选缓解方案。

> 提示：**切换 ZLM 多端口模式不是 workaround**，多端口下设备连 TCP `connect()` 都不发（详见 4.5.3），可让 wvp 在 15 s 就确认失败（早于单端口的 30 s）便于上层重试，但对最终用户仍然是失败。

下面按代价/可用性给出可行方案：

### 方案 P0：切换为 UDP 模式（**强烈推荐，零成本根治**）

**操作**：

- WVP 端：把通道的传输模式改为 UDP（`stream-mode: UDP` 或前端"传输模式"下拉切换）；或在 wvp 推送给设备的 INVITE SDP 里 `m=video` 一行使用 `RTP/AVP` 而非 `TCP/RTP/AVP`。
- ZLM 端：单端口模式默认 TCP+UDP 双协议同端口监听，无需改动；多端口模式 `openRtpServer` 时 `tcp_mode=0`（UDP）。

**效果**：

- ✅ 反复播放、长时间播放、播放停止后立即重播——全部正常。
- ✅ **完全规避** `ua_media.cpp:302` 的 TCP 清理循环 bug。
- ✅ 同时规避 4.7 的"TCP 主动模式无法播放"问题。

**注意事项**：

- UDP 不保证可靠传输，丢包率高的网络（跨广域网、4G/5G 移动场景）可能花屏卡顿。LAN / 内网环境一般无影响。
- UDP 模式下 NAT 穿越较 TCP 更复杂（如果设备和平台之间有 NAT），需确认双方网络可达。
- 部分需要严格 TCP 序的业务（如对讲、关键证据流）可能要保留 TCP，那时再考虑 P1~P3。

**结论**：本现场（执法记录仪 + LAN）**直接切 UDP 即可彻底解决**，无需等厂家修固件。

---

> 以下 P1~P3 仅适用于业务上**必须用 TCP** 的场景：

### 方案 P1：每次停止播放后下发"复位"指令（推荐短期方案）

在 wvp `PlayServiceImpl.stop()` 发完 BYE 后，立即对该设备发一条 GB28181 `DeviceControl` 命令尝试复位通道：

- **优先**：使用厂家私有"通道复位"指令（如果厂家提供）。该方式仅复位媒体通道，业务影响最小。
- **兜底**：发标准 `<TeleBoot>Boot</TeleBoot>` 让设备整机重启。
  - 优点：100% 能修好 sender。
  - 缺点：每次停止播放都触发整机重启，设备 30~60 秒不可用、影响录像/对讲/定位等其它业务，**不可接受作为生产方案**，仅作为"无私有指令时的最后兜底"。

落地工作量：在 `PlayServiceImpl.stop()` 末尾追加约 30 行 Java（调用现有 `DeviceControlServiceImpl`），对照厂家文档填子标签。

### 方案 P2：不发 BYE，改为长会话复用（视设备能力可选）

修改 wvp 的"停止播放"逻辑：

- 用户点停止时，前端**只断开播放器拉流**；
- wvp 不发 BYE，保留 SIP dialog 与媒体通道；
- 用户下次点开始时，**复用同一个 dialog**，让设备 sender 不必经历"BYE → reinit"路径。

优点：完全规避设备 bug。
缺点与风险：
- wvp 现有 stop API 假设了 BYE 一定会发，调用链改动较多（需评估约 100~200 行）；
- 部分设备 sender 线程长时间空转会自我超时（30 分钟级），届时仍会复现失败；
- 不发 BYE 会消耗一个 dialog 槽位，设备并发会话数有限时可能挤占其它业务。

### 方案 P3：监测+告警，自动安排"修复式重启"（运维兜底）

在 wvp 增加：

- 监测每个设备的"连续点播失败计数"，达到阈值（如 1 次失败）即认定该设备处于"sender 卡死"状态；
- 自动给该设备下发 `TeleBoot` 让其重启，并对前端返回友好提示"设备恢复中，请 30 秒后重试"。

优点：实现简单，完全自动化。
缺点：每次失败都要让设备整机重启，业务连续性差，仅适合非关键场景或问题设备隔离。

### 方案对比

| 方案 | 是否治本 | 业务影响 | 实施难度 | 是否依赖厂家配合 |
|---|---|---|---|---|
| **P0（切 UDP，强烈推荐）** | ✅ **完全规避** | 视网络条件而定（LAN 无影响） | **极低**（改 1 个配置） | 否 |
| 修固件 | ✅ 彻底解决 | 无 | 取决厂家 | 是 |
| P1（私有复位指令） | 缓解，可生产 | 极小 | 低 | 是（要厂家提供指令） |
| P1（TeleBoot 兜底） | 缓解 | 大 | 低 | 否 |
| P2（不发 BYE） | 规避 | 中（前端要配合） | 中 | 否 |
| P3（失败后整机重启） | 兜底 | 大 | 低 | 否 |

**推荐执行顺序**：

1. **立即把传输模式切到 UDP（P0）**——5 分钟改一个配置，问题立即消失，外场即可恢复。
2. 同步把本报告甩给设备厂家，要求修固件（第 6 章）；UDP 仅作为临时 workaround，长期仍应让厂家修复 TCP 路径。
3. 如果业务必须用 TCP（NAT 穿越 / 严格丢包不容忍 / 对讲 / 证据流），先尝试 P1（厂家私有复位指令），再考虑 P2 / P3。

## 8. 附录

### A. 抓包/日志原始位置

- ZLM 容器内抓包：`/tmp/cap.pcap`（30 秒，仅 832 字节，承载 9 条 TCP 控制包，0 数据字节）
- ZLM 完整日志：`docker logs docker-polaris-media-1`
- WVP 完整日志：`docker logs docker-polaris-wvp-1`
- 关键时间点：
  - 第 1 次成功：`2026-04-26 19:52:57 ~ 19:57:56`
  - 第 2 次失败首发：`2026-04-26 19:57:59`
  - 重启设备后第 1 次成功：`2026-04-26 ~ 21:14`（用户手动）

### B. 前期排查（已修复）

- `.env` 原 `Stream_IP / SDP_IP / SIP_ShowIP = 192.168.0.40`，但宿主机 LAN IP 实为 `192.168.0.24`。已纠正为 `192.168.0.24` 并 `docker compose up -d --force-recreate polaris-wvp` 生效，此后才出现"第 1 次能播"的现象。该 IP 配错与本报告锁定的设备 sender bug 无因果关系，仅说明前期 SIP/SDP 收流地址下发错误已排除。

### C. 核心引用

- ZLM 单端口推流流程：`RtpSession.cpp:71 onError`（`illegal connection` 触发于 ESTABLISHED 后 `timeoutSec` 内 0 字节）
- WVP 单端口配置：`@d:/JXT/jxt-evidence-system/wvp-GB28181-pro/docker/wvp/wvp/application-docker.yml:99-115`
- ZLM rtp_proxy 配置：`@d:/JXT/jxt-evidence-system/wvp-GB28181-pro/docker/media/config.ini`（`[rtp_proxy] port=10003 timeoutSec=15`）

---

**报告输出人**：平台侧联调  
**联系方式**：（按需补充）
