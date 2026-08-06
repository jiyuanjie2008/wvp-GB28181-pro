# 案例：SIP MESSAGE 超时过短导致语音广播提前失败

> 创建时间：2026-06-30
> 影响范围：`sip.timeout` 配置不生效的部署下，语音喊话（Broadcast）和对讲（Talk）功能
> 涉及组件：`SipConfig.java`、`SipSubscribe.java`、`application-docker.yml`

---

## 一、问题现象

语音喊话/对讲流程完成到一定程度后失败：

| 步骤 | 时间 | 状态 |
|------|------|------|
| 用户点击喊话 | 15:10:41.472 | ✅ |
| 浏览器 WebRTC 推流到 ZLM | 15:10:41.590 | ✅ |
| ZLM 广播流注册成功 | 15:10:41.777 | ✅ |
| WVP 发送 SIP MESSAGE Broadcast 到设备 | 15:10:41.801 | ✅ |
| **错误：广播发送失败** | **15:10:42.794** | **❌ 仅 0.993 秒！** |
| 设备回复 200 OK 到 MESSAGE | ~15:10:45 | ❌ 来晚了，catch 已被删 |

---

## 二、日志证据

### WVP 错误日志

```
15:10:42.794 ERROR PlayServiceImpl:1314 语音广播发送失败：消息超时未回复
15:10:42.796         PlayServiceImpl:1341 [停止对讲] 设备...
```

### 错误后设备 INVITE 到达（catch 已不存在）

```
15:10:45.654 设备 INVITE 到达 → catch 已销毁 → 判为"非语音广播"忽略
```

### 从 MESSAGE 发出到错误的时间线

```
41.801 → 42.794 = 993ms  ← 非常接近 Java 默认 1000ms
```

---

## 三、根因分析

### 3.1 代码中的超时机制

`SipConfig.java` 定义了超时时间：

```java
@Component
@ConfigurationProperties(prefix = "sip", ignoreInvalidFields = true)
public class SipConfig {
    private long timeout = 1000;  // Java 默认值 = 1000ms
}
```

`SipSubscribe.java` 通过 `DelayQueue` 实现超时：

```java
@Scheduled(fixedDelay = 200)   // 每 200ms 检查一次
public void execute(){
    while (!delayQueue.isEmpty()) {
        SipEvent take = delayQueue.take();  // 取出过期事件
        // 触发超时回调
        EventResult<Object> eventResult = new EventResult<>();
        eventResult.type = EventResultType.timeout;
        eventResult.msg = "消息超时未回复";
        take.getErrorEvent().response(eventResult);
    }
}
```

### 3.2 配置文件中的超时设置

`application-docker.yml`（绑定的配置）中已设置了 `timeout: 5000`：

```yaml
sip:
    ...
    # 命令发送等待回复的超时时间, 单位:毫秒
    timeout: 5000
```

但此值**未生效**，`SipConfig.timeout` 仍为 Java 默认值 `1000ms`——因为 `@ConfigurationProperties` 的 `ignoreInvalidFields = true` 会静默忽略绑定失败，导致配置未被应用。

### 3.3 竞态条件

```
时间轴：
41.801  MESSAGE 发出                    ← timer 开始计时
42.794  timeout 回调触发（1000ms）       ← catch 被删除
45.654  设备回复 200 OK 到达            ← catch 已不存在
        设备 INVITE 到达                ← 被判"非语音广播"忽略
```

设备大约需要 4 秒才回复 MESSAGE 的 200 OK。但 WVP 的 timer 在 1 秒后就超时了，删除了广播会话（`audioBroadcastCatch`）。当设备后续的 INVITE 到达时，WVP 找不到匹配的广播会话，判定为"非语音广播"并拒绝。

---

## 四、修复

### 修复 1：强制从配置读取值

**文件**：`src/main/java/com/genersoft/iot/vmp/conf/SipConfig.java`

```java
@Value("${sip.timeout:1000}")   // 强制从 Spring Environment 读取
private long timeout = 1000;     // 失败时回退到 1000ms
```

### 修复 2：确保配置文件中有适当的值

`application-docker.yml` 中已有注释说明此问题：

```yaml
# 命令发送等待回复的超时时间, 单位:毫秒
# 注意：部分设备(如TonMXAndroid)对Broadcast通知的200 OK回复较慢(~4秒)，
# 过短会导致语音喊话广播会话被提前拆除、设备INVITE被拒，需适当增大
timeout: 5000
```

### 修复后效果

`sip.timeout = 5000ms`，设备有充足时间回复 MESSAGE 的 200 OK，广播会话正常维持，后续 INVITE 能被正确处理。

---

## 五、为什么 `@ConfigurationProperties` 绑定失败

`SipConfig` 同时使用了 `@ConfigurationProperties(prefix = "sip")` 和 `@Component`。在 Spring Boot 3.x 中，`--spring.config.location` 指定文件后，仍会加载类路径中的 `application.yml`，导致两个配置文件合并。类路径 `application.yml` 中的 `active: 274-dev` 激活了额外的 profile，可能干扰了 `ConfigurationProperties` 的绑定。

使用 `@Value("${sip.timeout:1000}")` 直接注入更可靠——它直接从 Spring `Environment` 获取值，不受 `@ConfigurationProperties` 绑定顺序影响。

---

## 六、经验总结

1. **`@ConfigurationProperties` 不是万能的**：当存在多个配置文件和 profile 时，`@ConfigurationProperties` 的绑定可能不可靠。关键参数建议用 `@Value` 补充保护。

2. **SIP 消息超时是竞态条件的常见来源**：某些设备（尤其是经过 NAT 的）对 SIP 请求的响应可能慢于预期。WVP 的 `timeout` 默认值 1000ms 对局域网设备可能够用，但对经过 Docker NAT 的设备可能不够。

3. **不要只看 WVP 的业务日志**：错误日志"消息超时未回复"和业务日志"停止对讲"相隔仅 2ms——但设备 INVITE 在 3 秒后才到。如果不看完整时间线，很容易被后续的 RTP/端口问题误导。

4. **`@ConfigurationProperties` + `ignoreInvalidFields` 的风险**：`ignoreInvalidFields = true` 会让绑定静默失败，保留 Java 默认值，造成"我明明配了为什么没生效"的困惑。
