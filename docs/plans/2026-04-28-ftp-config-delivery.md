# FTP Server Config Delivery Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add a new HTTP endpoint `POST /api/ftp/config` that sends FTP server configuration to a ZX terminal via GB28181 SIP MESSAGE (`ServerCfgType/ftpServerCfgType`), waits for SIP 200 OK, and returns success/failure to the HTTP caller.

**Architecture:** Follows WVP's existing `SipSubscribe` pattern (same as `fronEndCmd` PTZ control). The terminal only replies with SIP 200 OK (no application-layer XML response), so we use `SipSubscribe.Event` callbacks instead of `MessageSubscribe`. A `DeferredResult<WVPResult<String>>` bridges the async SIP response back to the synchronous HTTP request with a 5-second timeout.

**Tech Stack:** Java 21, Spring Boot 3, JAIN-SIP, WVP's existing SIPCommander/sipSender/SipSubscribe infrastructure.

**Design Doc:** `docs/spec/glm-ftp-config-delivery-spec.md`

---

### Task 1: Add `ftpServerConfigCmd` to ISIPCommander interface

**Files:**
- Modify: `src/main/java/com/genersoft/iot/vmp/gb28181/transmit/cmd/ISIPCommander.java`

**Step 1: Add the method declaration**

Find an appropriate location in the interface (after `teleBootCmd` or `fronEndCmd` declarations). Add:

```java
/**
 * FTP服务器配置下发（私有扩展 ServerCfgType/ftpServerCfgType）
 *
 * @param device      视频设备
 * @param channelId   通道ID
 * @param ipv4Address FTP服务器IP地址
 * @param ftpPort     FTP服务器端口
 * @param userId      FTP登录用户名
 * @param userPasswd  FTP登录密码
 * @param okEvent     SIP 200 OK 回调
 * @param errorEvent  SIP 错误回调
 */
void ftpServerConfigCmd(Device device, String channelId, String ipv4Address, int ftpPort,
                        String userId, String userPasswd,
                        SipSubscribe.Event okEvent, SipSubscribe.Event errorEvent)
        throws InvalidArgumentException, SipException, ParseException;
```

**Step 2: Verify compilation**

Run: `cd /d/JXT/jxt-evidence-system/wvp-GB28181-pro && mvn compile -pl . -q 2>&1 | tail -5`
Expected: BUILD FAILURE (SIPCommander does not implement the new method yet). This is expected.

**Step 3: Commit**

```bash
git add src/main/java/com/genersoft/iot/vmp/gb28181/transmit/cmd/ISIPCommander.java
git commit -m "feat(ftp-config): add ftpServerConfigCmd to ISIPCommander interface"
```

---

### Task 2: Implement `ftpServerConfigCmd` in SIPCommander

**Files:**
- Modify: `src/main/java/com/genersoft/iot/vmp/gb28181/transmit/cmd/impl/SIPCommander.java`

**Context:** Reference the `fronEndCmd` method at line 182 (SipSubscribe pattern with okEvent/errorEvent) and `teleBootCmd` at line 663 (XML construction pattern).

**Step 1: Add the implementation**

Add this method right after `teleBootCmd` (after line 679):

```java
/**
 * FTP服务器配置下发（私有扩展 ServerCfgType/ftpServerCfgType）
 */
@Override
public void ftpServerConfigCmd(Device device, String channelId, String ipv4Address, int ftpPort,
                               String userId, String userPasswd,
                               SipSubscribe.Event okEvent, SipSubscribe.Event errorEvent)
        throws InvalidArgumentException, SipException, ParseException {

    int sn = (int) ((Math.random() * 9 + 1) * 100000);
    String charset = device.getCharset();

    StringBuffer cmdXml = new StringBuffer(300);
    cmdXml.append("<?xml version=\"1.0\" encoding=\"" + charset + "\"?>\r\n");
    cmdXml.append("<Control>\r\n");
    cmdXml.append("<CmdType>ServerCfgType</CmdType>\r\n");
    cmdXml.append("<SN>" + sn + "</SN>\r\n");
    cmdXml.append("<DeviceID>" + channelId + "</DeviceID>\r\n");
    cmdXml.append("<ServerType>ftpServerCfgType</ServerType>\r\n");
    cmdXml.append("<FtpServerCfgType>\r\n");
    cmdXml.append("<Ipv4Address>" + ipv4Address + "</Ipv4Address>\r\n");
    cmdXml.append("<FTPPort>" + ftpPort + "</FTPPort>\r\n");
    cmdXml.append("<UserId>" + userId + "</UserId>\r\n");
    cmdXml.append("<UserPasswd>" + userPasswd + "</UserPasswd>\r\n");
    cmdXml.append("</FtpServerCfgType>\r\n");
    cmdXml.append("</Control>\r\n");

    Request request = headerProvider.createMessageRequest(device, cmdXml.toString(),
            SipUtils.getNewViaTag(), SipUtils.getNewFromTag(), null,
            sipSender.getNewCallIdHeader(sipLayer.getLocalIp(device.getLocalIp()), device.getTransport()));
    sipSender.transmitRequest(sipLayer.getLocalIp(device.getLocalIp()), request, errorEvent, okEvent);
}
```

**Step 2: Verify compilation**

Run: `cd /d/JXT/jxt-evidence-system/wvp-GB28181-pro && mvn compile -pl . -q 2>&1 | tail -5`
Expected: BUILD SUCCESS

**Step 3: Commit**

```bash
git add src/main/java/com/genersoft/iot/vmp/gb28181/transmit/cmd/impl/SIPCommander.java
git commit -m "feat(ftp-config): implement ftpServerConfigCmd in SIPCommander"
```

---

### Task 3: Add `ftpServerConfig` to IDeviceService interface

**Files:**
- Modify: `src/main/java/com/genersoft/iot/vmp/gb28181/service/IDeviceService.java`

**Step 1: Add the method declaration**

Add after existing device control methods:

```java
/**
 * 下发FTP服务器配置到终端设备
 *
 * @param device      设备对象
 * @param channelId   通道ID
 * @param ipv4Address FTP服务器IP
 * @param ftpPort     FTP端口
 * @param userId      FTP用户名
 * @param userPasswd  FTP密码
 * @param callback    结果回调
 */
void ftpServerConfig(Device device, String channelId, String ipv4Address, int ftpPort,
                     String userId, String userPasswd, ErrorCallback<String> callback);
```

**Step 2: Verify compilation fails (expected, impl missing)**

Run: `cd /d/JXT/jxt-evidence-system/wvp-GB28181-pro && mvn compile -pl . -q 2>&1 | tail -5`
Expected: BUILD FAILURE

**Step 3: Commit**

```bash
git add src/main/java/com/genersoft/iot/vmp/gb28181/service/IDeviceService.java
git commit -m "feat(ftp-config): add ftpServerConfig to IDeviceService interface"
```

---

### Task 4: Implement `ftpServerConfig` in DeviceServiceImpl

**Files:**
- Modify: `src/main/java/com/genersoft/iot/vmp/gb28181/service/impl/DeviceServiceImpl.java`

**Context:** Follow the same pattern as existing methods like `record()` or `deviceStatus()` — validate device, delegate to sipCommander.

**Step 1: Add the implementation**

Find an appropriate location (after other device control methods) and add:

```java
@Override
public void ftpServerConfig(Device device, String channelId, String ipv4Address, int ftpPort,
                            String userId, String userPasswd, ErrorCallback<String> callback) {
    try {
        sipCommander.ftpServerConfigCmd(device, channelId, ipv4Address, ftpPort, userId, userPasswd,
                eventResult -> {
                    log.info("[FTP配置下发] 收到SIP 200 OK, 设备: {}, 通道: {}", device.getDeviceId(), channelId);
                    callback.run(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMsg(), null);
                },
                eventResult -> {
                    log.warn("[FTP配置下发] SIP发送失败, 设备: {}, 原因: {}", device.getDeviceId(), eventResult.msg);
                    callback.run(ErrorCode.ERROR100.getCode(), "发送失败，" + eventResult.msg, null);
                }
        );
    } catch (InvalidArgumentException | SipException | ParseException e) {
        log.error("[FTP配置下发] 异常, 设备: {}", device.getDeviceId(), e);
        callback.run(ErrorCode.ERROR100.getCode(), "发送失败，" + e.getMessage(), null);
    }
}
```

**Step 2: Verify compilation**

Run: `cd /d/JXT/jxt-evidence-system/wvp-GB28181-pro && mvn compile -pl . -q 2>&1 | tail -5`
Expected: BUILD SUCCESS

**Step 3: Commit**

```bash
git add src/main/java/com/genersoft/iot/vmp/gb28181/service/impl/DeviceServiceImpl.java
git commit -m "feat(ftp-config): implement ftpServerConfig in DeviceServiceImpl"
```

---

### Task 5: Add HTTP endpoint in DeviceQuery controller

**Files:**
- Modify: `src/main/java/com/genersoft/iot/vmp/gb28181/controller/DeviceQuery.java`

**Context:** The controller already has `DeferredResult` imports and the `deviceStatusApi` pattern at line 262. Follow that exact pattern.

**Step 1: Add the endpoint**

Add after `deviceStatusApi` (after line 277). The endpoint accepts a POST with JSON body:

```java
@Operation(summary = "下发FTP服务器配置", security = @SecurityRequirement(name = JwtUtils.HEADER))
@PostMapping("/ftp/config")
public DeferredResult<WVPResult<String>> ftpConfig(@RequestBody Map<String, Object> params) {
    String deviceId = (String) params.get("deviceId");
    String ipv4Address = (String) params.get("ipv4Address");
    String userId = (String) params.get("userId");
    String userPasswd = (String) params.get("userPasswd");
    Object ftpPortObj = params.get("ftpPort");

    Assert.hasText(deviceId, "deviceId不能为空");
    Assert.hasText(ipv4Address, "ipv4Address不能为空");
    Assert.hasText(userId, "userId不能为空");
    Assert.hasText(userPasswd, "userPasswd不能为空");
    Assert.notNull(ftpPortObj, "ftpPort不能为空");

    int ftpPort;
    if (ftpPortObj instanceof Number) {
        ftpPort = ((Number) ftpPortObj).intValue();
    } else {
        ftpPort = Integer.parseInt(ftpPortObj.toString());
    }
    Assert.isTrue(ftpPort > 0 && ftpPort <= 65535, "ftpPort必须在1-65535之间");

    Device device = deviceService.getDeviceByDeviceId(deviceId);
    Assert.notNull(device, "设备不存在");

    DeferredResult<WVPResult<String>> result = new DeferredResult<>(5000L);

    deviceService.ftpServerConfig(device, device.getDeviceId(), ipv4Address, ftpPort, userId, userPasswd,
            (code, msg, data) -> {
                result.setResult(new WVPResult<>(code, msg, data));
            });

    result.onTimeout(() -> {
        log.warn("[FTP配置下发] 操作超时, 设备未应答, {}", deviceId);
        result.setResult(WVPResult.fail(ErrorCode.ERROR486.getCode(), "设备未应答"));
    });

    return result;
}
```

Note: Uses `Map<String, Object>` for request body to avoid creating a new VO class (YAGNI). If a typed VO is preferred later, it can be refactored.

**Step 2: Verify compilation**

Run: `cd /d/JXT/jxt-evidence-system/wvp-GB28181-pro && mvn compile -pl . -q 2>&1 | tail -5`
Expected: BUILD SUCCESS

**Step 3: Commit**

```bash
git add src/main/java/com/genersoft/iot/vmp/gb28181/controller/DeviceQuery.java
git commit -m "feat(ftp-config): add POST /api/device/query/ftp/config endpoint"
```

---

### Task 6: Integration Test with Running System

**Prerequisites:** Docker containers running (`docker-compose up -d`), ZX terminal registered and online.

**Step 1: Build the project**

Run: `cd /d/JXT/jxt-evidence-system/wvp-GB28181-pro && mvn clean package -DskipTests -q`

**Step 2: Rebuild and restart WVP container**

Run: `cd /d/JXT/jxt-evidence-system/wvp-GB28181-pro/docker && docker-compose up -d --build polaris-wvp`

**Step 3: Verify the endpoint exists**

Run: `curl -s http://localhost:18978/api/device/query/ftp/config -X POST -H "Content-Type: application/json" -d '{"deviceId":"nonexistent","ipv4Address":"1.2.3.4","ftpPort":21,"userId":"test","userPasswd":"test"}'`
Expected: `{"code":100,"msg":"设备不存在","data":null}` (or 401 if auth required)

**Step 4: Test with a real device**

Using the registered device ID (`35020000201311000070`):

```bash
curl -s http://localhost:8080/api/device/query/ftp/config -X POST \
  -H "Content-Type: application/json" \
  -d '{
    "deviceId": "35020000201311000070",
    "ipv4Address": "192.168.1.100",
    "ftpPort": 52488,
    "userId": "admin",
    "userPasswd": "test123"
  }'
```

Expected: `{"code":0,"msg":"成功","data":null}` — and the WVP logs should show the SIP MESSAGE being sent and SIP 200 OK received.

**Step 5: Verify in WVP logs**

Run: `docker logs --since 30s docker-polaris-wvp-1 2>&1 | grep -E "(FTP配置下发|ServerCfgType|ftpServerCfgType)"`

Expected: Log lines showing the config was sent and 200 OK received.

**Step 6: Final commit (if any fixups needed)**

```bash
git add -A
git commit -m "feat(ftp-config): FTP server config delivery endpoint complete"
```
