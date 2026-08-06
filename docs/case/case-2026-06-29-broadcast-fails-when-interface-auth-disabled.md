# 案例：`interface-authentication=false` 时语音喊话/对讲失败

> 创建时间：2026-06-29
> 影响范围：关闭接口鉴权的部署下，语音喊话（Broadcast）和语音对讲（Talk）功能
> 涉及组件：polaris-wvp、`UserController.java`、`channelPlayer/index.vue`、`devicePlayer.vue`

---

## 一、背景

本部署中，WVP 的 `user-settings.interface-authentication` 被设为 `false`（关闭接口鉴权）。这是有意的——内网环境无需登录验证，简化运维。

但是在测试语音喊话/对讲时，前端弹窗"失败"，无法使用。

---

## 二、问题现象

点击"语音对讲"标签页的麦克风按钮，屏幕显示红色提示"失败"。

F12 → Network 中可见两个请求：

| 请求 | URL | 结果 |
|------|-----|------|
| 广播 API | `POST /api/play/broadcast/{deviceId}/{channelId}?timeout=30&broadcastMode=true` | `code: 0` ✅ 成功 |
| 用户信息 | `POST /api/user/userInfo` | `code: 100` ❌ 失败 |

广播 API 本身返回成功，拿到了 WebRTC 推流地址。但随后的 `/api/user/userInfo` 请求返回 `code: 100`，前端无法获取 `pushKey`，WebRTC 推流无法启动。

---

## 三、代码路径分析

### 3.1 喊话的完整调用链

```
用户点击麦克风按钮
  → broadcastStatusClick()
    → dispatch('play/broadcastStart', [deviceId, channelId, broadcastMode])
      → API /api/play/broadcast/{deviceId}/{channelId}
        → code: 0, data.streamInfo.rtc   ← 成功，拿到推流地址
    → startBroadcast(rtcUrl)
      → dispatch('user/getUserInfo')
        → API POST /api/user/userInfo
          → code: 100                     ← 失败，鉴权被禁用
      → data == null → return           ← 直接退出
```

### 3.2 为什么广播 API 成功但 userInfo 失败

**广播 API**（`PlayController.java:197`）直接调用 `playService.audioBroadcast()`，不检查用户身份：

```java
public AudioBroadcastResult broadcastApi(@PathVariable String deviceId, @PathVariable String channelId, Integer timeout, Boolean broadcastMode) {
    return playService.audioBroadcast(deviceId, channelId, broadcastMode);
    // 不需要 SecurityUtils.getUserInfo()
}
```

**用户信息 API**（`UserController.java:218`）调用了 `SecurityUtils.getUserInfo()`：

```java
public LoginUser getUserInfo() {
    LoginUser userInfo = SecurityUtils.getUserInfo();
    if (userInfo == null) {
        throw new ControllerException(ErrorCode.ERROR100);  // ← 这里报错
    }
    ...
}
```

### 3.3 `SecurityUtils.getUserInfo()` 为何返回 null

当 `interface-authentication=false` 时，Spring Security 不进行认证，`SecurityContextHolder` 中的 principal 为 `"anonymousUser"`。`SecurityUtils.getUserInfo()` 的检查逻辑：

```java
public static LoginUser getUserInfo(){
    Authentication authentication = getAuthentication();
    if(authentication != null){
        Object principal = authentication.getPrincipal();
        if(principal != null && !"anonymousUser".equals(principal.toString())){
            User user = (User) principal;
            return new LoginUser(user, LocalDateTime.now());
        }
    }
    return null;  // ← 匿名用户，返回 null
}
```

### 3.4 为什么需要 pushKey

`startBroadcast()` 拿到 `rtc` 地址后，需要用户 `pushKey` 生成 WebRTC 推流签名：

```javascript
startBroadcast(url) {
    this.$store.dispatch('user/getUserInfo').then((data) => {
        const pushKey = data.pushKey
        url += '&sign=' + crypto.createHash('md5').update(pushKey, 'utf8').digest('hex')
        this.broadcastRtc = new ZLMRTCClient.Endpoint({ zlmsdpUrl: url, ... })
    })
}
```

`sign` 参数用于 WVP 校验推流权限，必须用对应用户的 `pushKey` 生成。

---

## 四、根因

**`interface-authentication=false` 让 `/api/user/userInfo` 无法获取当前用户信息，导致前端拿不到 `pushKey`，WebRTC 推流签名无法生成，喊话中断。**

这不是 WVP 的 bug——当鉴权开启时，用户已登录，`SecurityUtils.getUserInfo()` 能正常返回用户信息。但当管理员主动关闭鉴权时，这条代码路径就被忽视了。

---

## 五、修复方案

### 修复：`UserController.getUserInfo()` 增加 fallback

**文件**：`src/main/java/com/genersoft/iot/vmp/vmanager/user/UserController.java`

当 `SecurityUtils.getUserInfo()` 返回 null（即鉴权关闭）时，改为查询管理员用户：

```java
@PostMapping("/userInfo")
public LoginUser getUserInfo() {
    LoginUser userInfo = SecurityUtils.getUserInfo();
    if (userInfo == null) {
        // 接口鉴权关闭时，取默认管理员用户
        User user = userService.getUserByUsername("admin");
        if (user != null) {
            return new LoginUser(user, LocalDateTime.now());
        }
        throw new ControllerException(ErrorCode.ERROR100);
    }
    User user = userService.getUser(userInfo.getUsername(), userInfo.getPassword());
    return new LoginUser(user, LocalDateTime.now());
}
```

**原理**：当鉴权关闭时，不存在"当前登录用户"的概念，但喊话功能又需要一个有效的 `pushKey`。此时返回默认 admin 用户的配置是最合理的——admin 用户一定有 `pushKey`，且鉴权关闭的部署场景下不需要区分用户权限。

### 附带发现：`channelPlayer/index.vue` 的前端 bug

在排查过程中，还发现 `channelPlayer/index.vue` 的 `broadcastStatusClick()` 方法在 dispatch 时遗漏了 `deviceId` 参数：

```javascript
// ❌ 只传了2个参数，导致参数错位
this.$store.dispatch('play/broadcastStart', [this.channelId, this.broadcastMode])
// store 期望 [deviceId, channelId, broadcastMode]
// 实际收到 [channelId, broadcastMode, undefined] → broadcastMode=undefined

// ✅ 正确的写法（与 devicePlayer.vue 一致）
this.$store.dispatch('play/broadcastStart', [this.deviceId, this.channelId, this.broadcastMode])
```

此问题影响**频道列表页面**的喊话功能（`channelPlayer/index.vue`），**设备播放弹窗**（`devicePlayer.vue`）无此问题。已在源码中修正，需重新编译前端后生效。

---

## 六、经验总结

1. **关闭鉴权 ≠ 无用户场景**：即使 `interface-authentication=false`，部分功能（如喊话的 `pushKey` 校验）仍依赖用户信息。关闭鉴权时需确保这些功能有 fallback。

2. **广播 API 的两阶段流程**：喊话/对讲分两步——先调 broadcast API 获取推流地址（同步），再调 userInfo 获取 pushKey（异步）。第一步成功不代表整体成功，排查时容易忽略第二步。

3. **诊断方法**：遇到前端显示"失败"时，优先查看 F12 → Network 中**所有**请求的响应，不要只看第一个 API。

4. **测试路径**：WVP 前端有两条路径可进入喊话——设备播放弹窗（`devicePlayer.vue`）和频道列表播放器（`channelPlayer/index.vue`）。两者的 dispatch 参数不同，排查时需区分测试。
