# 设备 firmware Bug 报告 — `ua_media.cpp:302` 资源清理循环缺陷

> 致：设备厂家研发工程师  
> 提交：平台侧（WVP-Pro / ZLMediaKit 接入方）  
> 日期：2026-04-27

---

## TL;DR（30 秒读完）

**问题范围（按媒体传输模式区分）**：

| 模式 | SDP | 表现 |
|---|---|---|
| **UDP** | `RTP/AVP` | ✅ 反复播放正常 |
| **TCP 被动** | `TCP/RTP/AVP` + `setup:passive` | ❌ 断电后仅第 1 次成功，之后必失败（本报告主问题）|
| **TCP 主动** | `TCP/RTP/AVP` + `setup:active` | ❌ 完全无法播放（独立缺陷）|

**Bug #1 位置（TCP 被动模式）**：`D:/camera_live/app/src/main/cpp/libgb28181/sip/ua_media.cpp:302`

**触发现象**：每次收到 SIP `BYE` 时，固件打印 WARN：

```
W GB28181: [D:/camera_live/app/src/main/cpp/libgb28181/sip/ua_media.cpp][302] 超过55次发送，弹出循环
```

**后果**：该循环触发 `break` 后，TCP RTP 发送子模块的资源清理不完整，导致**下一次 INVITE 进来时虽然信令正常、`liveState=2` 也正常，但底层 TCP RTP 不再向平台发送任何字节**。最终现象：用户每次断电重启设备后只能成功点播 1~2 次，之后所有点播均"30 秒收流超时"，**仅断电重启可恢复**。

**Bug #2（TCP 主动模式）**：完全无法播放，需厂家从头排查 TCP 客户端 `connect()` + RTP 发送链路。

**修复优先级**：高。已在外场出现。平台侧目前的 workaround 是切换为 UDP 模式（仅适用于网络条件允许的场景），长期必须修固件。

---

## 一、设备信息

| 项 | 值 |
|---|---|
| SIP ID | `35020000201311000002` |
| 设备型号 | Body Worn Camera（执法记录仪）|
| Native 库 | `libnative-lib.so`（在 APK `app/src/main/lib/armeabi-v7a/`）|
| 已确认源码路径 | `D:/camera_live/app/src/main/cpp/libgb28181/`（编译时绝对路径）|

---

## 二、直接证据：每次 BYE 必触发 WARN

平台抓取设备 logcat 实测（同一次断电启动后的连续两次 INVITE/BYE 周期）：

```
== 第 1 次 BYE 处理（线程 TID 2854）==
04-27 16:10:46.024  I  收 BYE → call_state=8
04-27 16:10:46.025  I  sua_call_state[112]: "在该状态应该停留几秒钟,或者收到BYE 200 OK"
04-27 16:10:46.086  W  ua_media.cpp:302  ★ 超过55次发送，弹出循环 ★      ← 62ms 后
04-27 16:10:46.087  I  sua_stop_media, sua_stop_audio sua[0] end.
04-27 16:10:46.089  I  sua_stop_used_sua, sua[0] start.
04-27 16:10:46.089  I  sua_set_idle_sua, p_sua=0xc7b835f4, index[0]
04-27 16:10:46.090  I  sua_cs_bye_sent[1091]: CSE_Hang_Recv 停止

== 第 2 次 BYE 处理（线程 TID 3014，不同线程）==
04-27 16:11:47.640  I  收 BYE → call_state=8
04-27 16:11:47.640  I  sua_call_state[112]: "在该状态应该停留几秒钟,或者收到BYE 200 OK"
04-27 16:11:47.702  W  ua_media.cpp:302  ★ 超过55次发送，弹出循环 ★      ← 62ms 后（一致！）
04-27 16:11:47.702  I  sua_stop_media, sua_stop_audio sua[0] end.
04-27 16:11:47.705  I  sua_stop_used_sua, sua[0] start.
04-27 16:11:47.705  I  sua_set_idle_sua, p_sua=0xc7b835f4, index[0]
04-27 16:11:47.705  I  sua_cs_bye_sent[1091]: CSE_Hang_Recv 停止
```

观察：

- **62ms 触发延迟在两次之间完全一致**——说明触发条件是同一段确定性循环
- **触发线程 TID 不同（2854 → 3014）**——不是单一线程残留，是循环逻辑本身
- 警告之后才走 `sua_stop_media → sua_stop_used_sua → sua_set_idle_sua` 清理链

---

## 三、嫌疑代码模式

```c
// D:/camera_live/app/src/main/cpp/libgb28181/sip/ua_media.cpp  附近 line 302
void some_send_or_flush_routine(...) {
    int retry = 0;
    while (有数据待处理) {           // 可能是: 队列非空 / 等线程退出 / 等响应
        int ret = send_or_flush(...);
        retry++;
        if (retry > 55) {            // ★ 55 次硬编码上限 ★
            LOGW("超过55次发送，弹出循环");
            break;                   // ★ 强制跳出，跳过后续清理 ★
        }
    }
    // 关键资源清理代码（仅在循环正常结束时执行）：
    //   - rtp_tx 发送线程 join
    //   - send buffer free
    //   - SSRC / seq / ts 归零
    //   - socket fd close
    //   - 各种 static / 全局状态变量复位
    //
    // 一旦从 line 302 的 break 跳出 → 这些清理被跳过 → 状态残留 → 下次 init 看到脏状态
}
```

请按你们的源码索引在 `ua_media.cpp:302` 附近确认这段循环的实际语义。

---

## 四、为什么这个 bug 解释了所有外场现象

| 外场观察 | `ua_media.cpp:302` 模型的解释 |
|---|---|
| 同样 BYE 报文，有时第 2 次成功、有时第 2 次失败 | 取决于 BYE 触发瞬间发送队列里的瞬时长度——队列短可能正常结束，队列长触发 break |
| 推流时长长（5min）后停止，更容易出问题 | 推流越久发送队列累计越深，越易顶到 55 次上限 |
| 断电重启后只能成功 1~2 次 | 残留 static 状态随每次 BYE 累加，直到 init 路径完全失效 |
| 等再久也恢复不了，仅断电重启可恢复 | 残留的 static / 全局变量需要进程重启才能复位 |
| 多端口模式下连 TCP SYN 都不发 | 残留状态污染了 sender 的目标端口/socket 解析路径 |
| 设备无 ERROR 日志、`liveState=2` 仍切换正常 | bug 是 WARN 级，SUA / 应用层流程都跑完了，仅底层 RTP buffer 状态残留 |

---

## 五、平台侧已排除的 6 项（确保不是接入方问题）

| 假设 | 排除依据 |
|---|---|
| 网络丢包 / 防火墙 | 抓包确认 TCP 三次握手成功，0 字节 RTP；第 1 次能用 |
| Docker 端口映射问题 | 容器 `eth0` 抓到的就是设备实际报文 |
| WVP 信令问题 | 两次 INVITE/BYE 字段对比完全等价（CSeq 连续、Call-ID 唯一）|
| ZLM 单端口 RTP 配置 | 第 1 次正常拉起 `on_publish` + 改 stream id，证明配置链路通 |
| BYE 报文不兼容 | 设备 SIP 状态机正确切到 8、清理流程跑完——证明 BYE 解析无问题 |
| 等待时间不够 | 实测等 1 小时 12 分钟仍失败 |

详细排除证据见完整版报告（`2026-04-26_设备RTP-Sender未复位_问题定位报告.md`），可索取。

---

## 六、修复指引

### 6.1 必须做的事

1. **找到 `ua_media.cpp:302` 这段循环的真实语义**：是 flush 发送队列？等线程退出？等 SIP 响应？不同语义对应不同修复路径。

2. **`break` 路径必须等价于循环正常结束路径**——保证下面的资源清理代码无论从哪个出口都执行：

   ```c
   // 修复模式 A: 显式补全清理
   if (retry > 55) {
       LOGW("超过55次发送，弹出循环 (强制清理)");
       force_cleanup_rtp_tx();      // 显式释放 RTP buffer / socket
       force_join_send_thread();    // 显式 join 发送线程
       memset(&g_rtp_state, 0, sizeof(g_rtp_state));   // 复位所有 static
       break;
   }
   ```

3. **更稳的修复（推荐）**：把 55 次次数上限改成基于时间的超时：

   ```c
   uint64_t deadline_ms = now_ms() + 1000;  // 1 秒超时
   while (有数据待处理 && now_ms() < deadline_ms) {
       send_or_flush(...);
   }
   if (now_ms() >= deadline_ms) {
       LOGW("flush 超时, 强制清理");
   }
   force_cleanup_rtp_tx();          // 无条件清理，不依赖循环结束方式
   ```

4. **加详细日志**便于复现验证：

   ```c
   LOGW("超过55次发送，弹出循环: queue_len=%d, sock_fd=%d, sender_thread=%p",
        queue_size(), g_sock_fd, g_send_thread);
   ```

### 6.2 验收标准

- 连续 INVITE → 推流 → BYE → 等 5s → INVITE 循环 **100 次以上**，每次都能成功收到 RTP 字节
- 改变第 1 次推流时长（5s / 30s / 5min），后续每次播放都能成功
- 不再出现 `超过55次发送，弹出循环` WARN（或弹出后底层状态确实被清理干净）

### 6.3 期待回复

请确认以下任意一项：

1. **修复路径**：在 ua_media.cpp:302 附近做了什么改动，预计哪个版本发布
2. **该循环的语义解释**：如果你们认为这段循环不是 bug，请解释每次 BYE 必触发 55 次上限的原因
3. **临时 workaround**：固件层面是否有任何配置 / SIP 扩展信令能强制让设备完整 reset RTP 子模块（除了断电重启）

---

## 七、附录：复现方法

任何能让设备触发 INVITE/BYE 周期的工具都能复现：

```
1. 设备断电重启
2. 平台发 INVITE → 设备推流 → 等 10~30s → 平台发 BYE
3. 平台再发 INVITE → 等 30s
4. 观察平台是否收到 RTP 字节 (tcpdump / Wireshark)
5. 同时 logcat 抓 GB28181 tag, 必看到 ua_media.cpp:302 WARN
```

平台侧抓 logcat 命令：

```bash
adb shell logcat -G 16M
adb shell logcat -c
# ... 操作步骤 2-3 ...
adb shell "logcat -d -v threadtime GB28181:V Gb28181Local:V '*:S'" > device_log.txt
grep "ua_media.cpp" device_log.txt
```

---

**联系方式**：（请按你们流程填写）
