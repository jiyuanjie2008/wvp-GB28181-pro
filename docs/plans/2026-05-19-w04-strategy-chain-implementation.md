# W04 Strategy Chain Authentication — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Implement HA1 digest authentication for SIP REGISTER via a strategy chain, enabling IAM-pushed 5G body cameras to register with pre-shared HA1 credentials while preserving backward compatibility with all existing devices.

**Architecture:** Strategy chain pattern — `Ha1Strategy` (priority 1) tries HA1 digest auth, `PlaintextStrategy` (priority 2) falls back to plaintext password auth. `DeviceAuthStrategyChain` dispatches to the first non-SKIP strategy. `RegisterRequestProcessor` delegates to the chain when `jxt.identity.enabled=true`, or falls back to legacy path when disabled. Kill switch requires restart (`@Value` injected at bean creation) — chain Bean always exists. 401 challenge is handled in the caller (not in helper methods) to prevent double-response bugs.

**Tech Stack:** Java 21, Spring Boot 3.4.4, JAIN-SIP (`SIPRequest` as strategy input), `DigestServerAuthenticationHelper` (existing, reuse directly), MyBatis, JUnit 5 + Mockito, Lombok

**Design docs:**
- `docs/plans/2026-05-16-iteration2-wvp-design.md` (§5 — design reference)
- `docs/plans/2026-05-16-iteration2-plus-wvp-implementation.md` (original plus plan)

**Eng review decisions (plan-eng-review 2026-05-19):**

| Decision | Choice | Rationale |
|----------|--------|-----------|
| D1 Scope | Keep strategy chain | disabled/activated/rotation are known follow-up strategies |
| D2 Strategy input | `SIPRequest` | Reuse existing request-based digest helpers; no extra DTO |
| D3 Kill switch | Restart-required legacy fallback | `jxt.identity.enabled=false` routes through legacy path (requires restart) |
| D4 Bean lifecycle | Chain Bean always exists | Avoid `NoSuchBeanDefinitionException`; flag controls calls, not creation |
| D5 Thread safety | `new DigestServerAuthenticationHelper()` per call | `MessageDigest` field is NOT thread-safe; must NOT be singleton bean |
| D6 401 flow | 401 challenge extracted to caller | Prevents 401+403 double-send; helpers are pure boolean verification |

---

## Task 1: AuthResult Enum

**Files:**
- Create: `src/main/java/com/genersoft/iot/vmp/jxt/identity/auth/AuthResult.java`
- Create: `src/test/java/com/genersoft/iot/vmp/jxt/identity/auth/AuthResultTest.java`

**Step 1: Write the test**

```java
package com.genersoft.iot.vmp.jxt.identity.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AuthResultTest {

    @Test
    void enumHasExactlyThreeValues() {
        AuthResult[] values = AuthResult.values();
        assertEquals(3, values.length);
        assertArrayEquals(new AuthResult[]{AuthResult.SUCCESS, AuthResult.FAIL, AuthResult.SKIP}, values);
    }

    @Test
    void valueOfWorks() {
        assertEquals(AuthResult.SUCCESS, AuthResult.valueOf("SUCCESS"));
        assertEquals(AuthResult.FAIL, AuthResult.valueOf("FAIL"));
        assertEquals(AuthResult.SKIP, AuthResult.valueOf("SKIP"));
    }
}
```

**Step 2: Run test to verify it fails**

Run: `mvn test -pl . -Dtest="AuthResultTest" -DskipTests=false`
Expected: FAIL — `AuthResult` class does not exist.

**Step 3: Write implementation**

```java
package com.genersoft.iot.vmp.jxt.identity.auth;

public enum AuthResult {
    SUCCESS,
    FAIL,
    SKIP
}
```

**Step 4: Run test to verify it passes**

Run: `mvn test -pl . -Dtest="AuthResultTest" -DskipTests=false`
Expected: PASS

**Step 5: Commit**

```bash
git add src/main/java/com/genersoft/iot/vmp/jxt/identity/auth/AuthResult.java src/test/java/com/genersoft/iot/vmp/jxt/identity/auth/AuthResultTest.java
git commit -m "feat(W04): add AuthResult enum — SUCCESS/FAIL/SKIP"
```

---

## Task 2: DeviceAuthStrategy Interface

**Files:**
- Create: `src/main/java/com/genersoft/iot/vmp/jxt/identity/auth/DeviceAuthStrategy.java`

**Step 1: Write the interface**

```java
package com.genersoft.iot.vmp.jxt.identity.auth;

import com.genersoft.iot.vmp.gb28181.bean.Device;
import gov.nist.javax.sip.message.SIPRequest;

public interface DeviceAuthStrategy {
    int priority();
    AuthResult authenticate(Device device, SIPRequest request);
}
```

**Step 2: Verify compilation**

Run: `mvn compile -pl . -q`
Expected: BUILD SUCCESS.

**Step 3: Commit**

```bash
git add src/main/java/com/genersoft/iot/vmp/jxt/identity/auth/DeviceAuthStrategy.java
git commit -m "feat(W04): add DeviceAuthStrategy interface — SIPRequest-based strategy contract"
```

---

## Task 3: Ha1Strategy

**Files:**
- Create: `src/main/java/com/genersoft/iot/vmp/jxt/identity/auth/Ha1Strategy.java`
- Create: `src/test/java/com/genersoft/iot/vmp/jxt/identity/auth/Ha1StrategyTest.java`

**Step 1: Write the test**

```java
package com.genersoft.iot.vmp.jxt.identity.auth;

import com.genersoft.iot.vmp.gb28181.auth.DigestServerAuthenticationHelper;
import com.genersoft.iot.vmp.gb28181.bean.Device;
import gov.nist.javax.sip.message.SIPRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

// D5: Tests use anonymous subclass to override createHelper() instead of @Autowired field
class Ha1StrategyTest {

    private Ha1Strategy strategy;
    private DigestServerAuthenticationHelper digestHelper;
    private Device device;

    @BeforeEach
    void setUp() {
        digestHelper = mock(DigestServerAuthenticationHelper.class);
        strategy = new Ha1Strategy() {
            @Override
            DigestServerAuthenticationHelper createHelper() { return digestHelper; }
        };
        device = new Device();
        device.setDeviceId("34020000001320000001");
    }

    @Test
    void priorityIs1() {
        assertEquals(1, strategy.priority());
    }

    @Test
    void sipHa1Null_returnsSKIP() {
        device.setSipHa1(null);
        SIPRequest request = mock(SIPRequest.class);
        assertEquals(AuthResult.SKIP, strategy.authenticate(device, request));
        verifyNoInteractions(digestHelper);
    }

    @Test
    void sipHa1Empty_returnsSKIP() {
        device.setSipHa1("");
        SIPRequest request = mock(SIPRequest.class);
        assertEquals(AuthResult.SKIP, strategy.authenticate(device, request));
        verifyNoInteractions(digestHelper);
    }

    @Test
    void digestSuccess_returnsSUCCESS() throws Exception {
        device.setSipHa1("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4");
        SIPRequest request = mock(SIPRequest.class);
        when(digestHelper.doAuthenticateHashedPassword(any(), eq("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4"))).thenReturn(true);
        assertEquals(AuthResult.SUCCESS, strategy.authenticate(device, request));
    }

    @Test
    void digestFail_returnsFAIL() throws Exception {
        device.setSipHa1("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4");
        SIPRequest request = mock(SIPRequest.class);
        when(digestHelper.doAuthenticateHashedPassword(any(), eq("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4"))).thenReturn(false);
        assertEquals(AuthResult.FAIL, strategy.authenticate(device, request));
    }

    @Test
    void digestThrows_returnsFAIL() throws Exception {
        device.setSipHa1("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4");
        SIPRequest request = mock(SIPRequest.class);
        when(digestHelper.doAuthenticateHashedPassword(any(), anyString())).thenThrow(new RuntimeException("test error"));
        assertEquals(AuthResult.FAIL, strategy.authenticate(device, request));
    }
}
```

**Step 2: Run test to verify it fails**

Run: `mvn test -pl . -Dtest="Ha1StrategyTest" -DskipTests=false`
Expected: FAIL — `Ha1Strategy` class does not exist.

**Step 3: Write implementation**

```java
package com.genersoft.iot.vmp.jxt.identity.auth;

import com.genersoft.iot.vmp.gb28181.auth.DigestServerAuthenticationHelper;
import com.genersoft.iot.vmp.gb28181.bean.Device;
import gov.nist.javax.sip.message.SIPRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.ObjectUtils;

import java.security.NoSuchAlgorithmException;

@Slf4j
@Component
@ConditionalOnProperty(value = "jxt.identity.strategy.ha1-enabled", havingValue = "true", matchIfMissing = true)
public class Ha1Strategy implements DeviceAuthStrategy {

    // D5: NOT a singleton bean — MessageDigest is NOT thread-safe.
    // Each call creates a new DigestServerAuthenticationHelper instance.
    DigestServerAuthenticationHelper createHelper() {
        try {
            return new DigestServerAuthenticationHelper();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 algorithm not available", e);
        }
    }

    @Override
    public int priority() {
        return 1;
    }

    @Override
    public AuthResult authenticate(Device device, SIPRequest request) {
        if (ObjectUtils.isEmpty(device.getSipHa1())) {
            return AuthResult.SKIP;
        }
        try {
            boolean ok = createHelper().doAuthenticateHashedPassword(request, device.getSipHa1());
            if (ok) {
                log.debug("Ha1Strategy: device {} authenticated via HA1 digest", device.getDeviceId());
                return AuthResult.SUCCESS;
            }
            log.warn("Ha1Strategy: device {} HA1 digest verification failed", device.getDeviceId());
            return AuthResult.FAIL;
        } catch (Exception e) {
            log.error("Ha1Strategy: error authenticating device {}", device.getDeviceId(), e);
            return AuthResult.FAIL;
        }
    }
}
```

**Step 4: Run test to verify it passes**

Run: `mvn test -pl . -Dtest="Ha1StrategyTest" -DskipTests=false`
Expected: PASS (5 tests)

**Step 5: Commit**

```bash
git add src/main/java/com/genersoft/iot/vmp/jxt/identity/auth/Ha1Strategy.java src/test/java/com/genersoft/iot/vmp/jxt/identity/auth/Ha1StrategyTest.java
git commit -m "feat(W04): add Ha1Strategy — HA1 digest authentication for IAM-pushed devices"
```

---

## Task 4: PlaintextStrategy

**Files:**
- Create: `src/main/java/com/genersoft/iot/vmp/jxt/identity/auth/PlaintextStrategy.java`
- Create: `src/test/java/com/genersoft/iot/vmp/jxt/identity/auth/PlaintextStrategyTest.java`

**Step 1: Write the test**

```java
package com.genersoft.iot.vmp.jxt.identity.auth;

import com.genersoft.iot.vmp.gb28181.auth.DigestServerAuthenticationHelper;
import com.genersoft.iot.vmp.gb28181.bean.Device;
import gov.nist.javax.sip.message.SIPRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

// D5: Tests use anonymous subclass to override createHelper() instead of @Autowired field
class PlaintextStrategyTest {

    private PlaintextStrategy strategy;
    private DigestServerAuthenticationHelper digestHelper;
    private Device device;

    @BeforeEach
    void setUp() {
        digestHelper = mock(DigestServerAuthenticationHelper.class);
        strategy = new PlaintextStrategy() {
            @Override
            DigestServerAuthenticationHelper createHelper() { return digestHelper; }
        };
        device = new Device();
        device.setDeviceId("34020000001320000001");
    }

    @Test
    void priorityIs2() {
        assertEquals(2, strategy.priority());
    }

    @Test
    void passwordNull_returnsSKIP() {
        device.setPassword(null);
        SIPRequest request = mock(SIPRequest.class);
        assertEquals(AuthResult.SKIP, strategy.authenticate(device, request));
        verifyNoInteractions(digestHelper);
    }

    @Test
    void passwordEmpty_returnsSKIP() {
        device.setPassword("");
        SIPRequest request = mock(SIPRequest.class);
        assertEquals(AuthResult.SKIP, strategy.authenticate(device, request));
        verifyNoInteractions(digestHelper);
    }

    @Test
    void digestSuccess_returnsSUCCESS() throws Exception {
        device.setPassword("camera123");
        SIPRequest request = mock(SIPRequest.class);
        when(digestHelper.doAuthenticatePlainTextPassword(any(), eq("camera123"))).thenReturn(true);
        assertEquals(AuthResult.SUCCESS, strategy.authenticate(device, request));
    }

    @Test
    void digestFail_returnsFAIL() throws Exception {
        device.setPassword("camera123");
        SIPRequest request = mock(SIPRequest.class);
        when(digestHelper.doAuthenticatePlainTextPassword(any(), eq("camera123"))).thenReturn(false);
        assertEquals(AuthResult.FAIL, strategy.authenticate(device, request));
    }

    @Test
    void digestThrows_returnsFAIL() throws Exception {
        device.setPassword("camera123");
        SIPRequest request = mock(SIPRequest.class);
        when(digestHelper.doAuthenticatePlainTextPassword(any(), anyString())).thenThrow(new RuntimeException("test error"));
        assertEquals(AuthResult.FAIL, strategy.authenticate(device, request));
    }
}
```

**Step 2: Run test to verify it fails**

Run: `mvn test -pl . -Dtest="PlaintextStrategyTest" -DskipTests=false`
Expected: FAIL — `PlaintextStrategy` class does not exist.

**Step 3: Write implementation**

```java
package com.genersoft.iot.vmp.jxt.identity.auth;

import com.genersoft.iot.vmp.gb28181.auth.DigestServerAuthenticationHelper;
import com.genersoft.iot.vmp.gb28181.bean.Device;
import gov.nist.javax.sip.message.SIPRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.ObjectUtils;

import java.security.NoSuchAlgorithmException;

@Slf4j
@Component
@ConditionalOnProperty(value = "jxt.identity.strategy.plaintext-enabled", havingValue = "true", matchIfMissing = true)
public class PlaintextStrategy implements DeviceAuthStrategy {

    // D5: NOT a singleton bean — MessageDigest is NOT thread-safe.
    DigestServerAuthenticationHelper createHelper() {
        try {
            return new DigestServerAuthenticationHelper();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 algorithm not available", e);
        }
    }

    @Override
    public int priority() {
        return 2;
    }

    @Override
    public AuthResult authenticate(Device device, SIPRequest request) {
        String password = device.getPassword();
        if (ObjectUtils.isEmpty(password)) {
            return AuthResult.SKIP;
        }
        try {
            boolean ok = createHelper().doAuthenticatePlainTextPassword(request, password);
            if (ok) {
                log.debug("PlaintextStrategy: device {} authenticated via plaintext password", device.getDeviceId());
                return AuthResult.SUCCESS;
            }
            log.warn("PlaintextStrategy: device {} password verification failed", device.getDeviceId());
            return AuthResult.FAIL;
        } catch (Exception e) {
            log.error("PlaintextStrategy: error authenticating device {}", device.getDeviceId(), e);
            return AuthResult.FAIL;
        }
    }
}
```

**Step 4: Run test to verify it passes**

Run: `mvn test -pl . -Dtest="PlaintextStrategyTest" -DskipTests=false`
Expected: PASS (6 tests)

**Step 5: Commit**

```bash
git add src/main/java/com/genersoft/iot/vmp/jxt/identity/auth/PlaintextStrategy.java src/test/java/com/genersoft/iot/vmp/jxt/identity/auth/PlaintextStrategyTest.java
git commit -m "feat(W04): add PlaintextStrategy — legacy password fallback for existing devices"
```

---

## Task 5: DeviceAuthStrategyChain

**Files:**
- Create: `src/main/java/com/genersoft/iot/vmp/jxt/identity/auth/DeviceAuthStrategyChain.java`
- Create: `src/test/java/com/genersoft/iot/vmp/jxt/identity/auth/DeviceAuthStrategyChainTest.java`

**Step 1: Write the test**

```java
package com.genersoft.iot.vmp.jxt.identity.auth;

import com.genersoft.iot.vmp.gb28181.bean.Device;
import gov.nist.javax.sip.message.SIPRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DeviceAuthStrategyChainTest {

    private final Device device = new Device();
    private final SIPRequest request = mock(SIPRequest.class);

    @Test
    void strategiesSortedByPriority() {
        DeviceAuthStrategy low = mock(DeviceAuthStrategy.class);
        when(low.priority()).thenReturn(5);
        DeviceAuthStrategy high = mock(DeviceAuthStrategy.class);
        when(high.priority()).thenReturn(1);

        DeviceAuthStrategyChain chain = new DeviceAuthStrategyChain(List.of(low, high));
        // Internal verification: chain should process high before low
        when(high.authenticate(any(), any())).thenReturn(AuthResult.SUCCESS);
        chain.authenticate(device, request);

        verify(high).authenticate(device, request);
        verify(low, never()).authenticate(any(), any());
    }

    @Test
    void firstNonSKIPStopsChain_returnsSUCCESS() {
        DeviceAuthStrategy s1 = mock(DeviceAuthStrategy.class);
        when(s1.priority()).thenReturn(1);
        when(s1.authenticate(any(), any())).thenReturn(AuthResult.SKIP);

        DeviceAuthStrategy s2 = mock(DeviceAuthStrategy.class);
        when(s2.priority()).thenReturn(2);
        when(s2.authenticate(any(), any())).thenReturn(AuthResult.SUCCESS);

        DeviceAuthStrategy s3 = mock(DeviceAuthStrategy.class);
        when(s3.priority()).thenReturn(3);

        DeviceAuthStrategyChain chain = new DeviceAuthStrategyChain(List.of(s1, s2, s3));
        AuthResult result = chain.authenticate(device, request);

        assertEquals(AuthResult.SUCCESS, result);
        verify(s1).authenticate(device, request);
        verify(s2).authenticate(device, request);
        verify(s3, never()).authenticate(any(), any());
    }

    @Test
    void allSKIP_returnsSKIP() {
        DeviceAuthStrategy s1 = mock(DeviceAuthStrategy.class);
        when(s1.priority()).thenReturn(1);
        when(s1.authenticate(any(), any())).thenReturn(AuthResult.SKIP);

        DeviceAuthStrategy s2 = mock(DeviceAuthStrategy.class);
        when(s2.priority()).thenReturn(2);
        when(s2.authenticate(any(), any())).thenReturn(AuthResult.SKIP);

        DeviceAuthStrategyChain chain = new DeviceAuthStrategyChain(List.of(s1, s2));
        assertEquals(AuthResult.SKIP, chain.authenticate(device, request));
    }

    @Test
    void emptyStrategies_returnsSKIP() {
        DeviceAuthStrategyChain chain = new DeviceAuthStrategyChain(List.of());
        assertEquals(AuthResult.SKIP, chain.authenticate(device, request));
    }

    @Test
    void firstStrategyFAIL_stopsChain() {
        DeviceAuthStrategy s1 = mock(DeviceAuthStrategy.class);
        when(s1.priority()).thenReturn(1);
        when(s1.authenticate(any(), any())).thenReturn(AuthResult.FAIL);

        DeviceAuthStrategy s2 = mock(DeviceAuthStrategy.class);
        when(s2.priority()).thenReturn(2);

        DeviceAuthStrategyChain chain = new DeviceAuthStrategyChain(List.of(s1, s2));
        assertEquals(AuthResult.FAIL, chain.authenticate(device, request));
        verify(s2, never()).authenticate(any(), any());
    }
}
```

**Step 2: Run test to verify it fails**

Run: `mvn test -pl . -Dtest="DeviceAuthStrategyChainTest" -DskipTests=false`
Expected: FAIL — `DeviceAuthStrategyChain` class does not exist.

**Step 3: Write implementation**

```java
package com.genersoft.iot.vmp.jxt.identity.auth;

import com.genersoft.iot.vmp.gb28181.bean.Device;
import gov.nist.javax.sip.message.SIPRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

@Slf4j
@Component
public class DeviceAuthStrategyChain {

    private final List<DeviceAuthStrategy> strategies;

    public DeviceAuthStrategyChain(List<DeviceAuthStrategy> strategies) {
        this.strategies = strategies.stream()
                .sorted(Comparator.comparingInt(DeviceAuthStrategy::priority))
                .toList();
        log.info("DeviceAuthStrategyChain initialized with {} strategies: {}",
                this.strategies.size(),
                this.strategies.stream().map(s -> s.getClass().getSimpleName()).toList());
    }

    public AuthResult authenticate(Device device, SIPRequest request) {
        for (DeviceAuthStrategy strategy : strategies) {
            AuthResult result = strategy.authenticate(device, request);
            if (result != AuthResult.SKIP) {
                return result;
            }
        }
        return AuthResult.SKIP;
    }
}
```

**Step 4: Run test to verify it passes**

Run: `mvn test -pl . -Dtest="DeviceAuthStrategyChainTest" -DskipTests=false`
Expected: PASS (5 tests)

**Step 5: Commit**

```bash
git add src/main/java/com/genersoft/iot/vmp/jxt/identity/auth/DeviceAuthStrategyChain.java src/test/java/com/genersoft/iot/vmp/jxt/identity/auth/DeviceAuthStrategyChainTest.java
git commit -m "feat(W04): add DeviceAuthStrategyChain — priority-sorted strategy dispatch"
```

---

## Task 6: RegisterRequestProcessor Refactor

This is the most critical task. The refactor replaces lines 116–177 of `RegisterRequestProcessor.java` with strategy-chain-aware logic behind a feature flag. The re-registration fast path (lines 117–138) is preserved unchanged.

**Files:**
- Modify: `src/main/java/com/genersoft/iot/vmp/gb28181/transmit/event/request/impl/RegisterRequestProcessor.java`

**Step 1: Add new imports and dependencies**

Add these imports after the existing import block (after line 42):

```java
import com.genersoft.iot.vmp.jxt.identity.auth.AuthResult;
import com.genersoft.iot.vmp.jxt.identity.auth.DeviceAuthStrategyChain;
import org.springframework.beans.factory.annotation.Value;
```

Add these fields after the existing `@Autowired` fields (after line 72):

```java
@Autowired
private DeviceAuthStrategyChain strategyChain;

@Value("${jxt.identity.enabled:true}")
private boolean identityEnabled;
```

Note (D5): `DigestServerAuthenticationHelper` is NOT autowired. It is created with `new` per call because its internal `MessageDigest` field is NOT thread-safe.

**Step 2: Replace the authentication block**

Replace the code from line 116 through line 177 (from `if (device != null) {` through the closing `}` of the `if (!passwordCorrect)` block) with:

```java
            String password = null;
            if (device != null) {
                if (device.getSipTransactionInfo() != null &&
                        request.getCallIdHeader().getCallId().equals(device.getSipTransactionInfo().getCallId())) {
                    log.info("{} 设备：{}, 注册续订: {}", title, device.getDeviceId(), device.getDeviceId());
                    if (registerFlag) {
                        device.setExpires(request.getExpires().getExpires());
                        device.setIp(remoteAddressInfo.getIp());
                        device.setPort(remoteAddressInfo.getPort());
                        device.setHostAddress(IpPortUtil.concatenateIpAndPort(remoteAddressInfo.getIp(), String.valueOf(remoteAddressInfo.getPort())));

                        device.setLocalIp(request.getLocalAddress().getHostAddress());
                        Response registerOkResponse = getRegisterOkResponse(request);
                        // 判断TCP还是UDP
                        ViaHeader reqViaHeader = (ViaHeader) request.getHeader(ViaHeader.NAME);
                        String transport = reqViaHeader.getTransport();
                        device.setTransport("TCP".equalsIgnoreCase(transport) ? "TCP" : "UDP");
                        sipSender.transmitRequest(request.getLocalAddress().getHostAddress(), registerOkResponse);
                        device.setRegisterTimeStamp(System.currentTimeMillis());
                        deviceService.online(device);
                    } else {
                        deviceService.offline(device);
                    }
                    return;
                }else {
                    // 正常注册, 用户信息未设置密码，并且公共密码也未设置，则关闭鉴权
                    if (!ObjectUtils.isEmpty(device.getPassword()) || !ObjectUtils.isEmpty(sipConfig.getPassword())) {
                        password = (!ObjectUtils.isEmpty(device.getPassword())) ? device.getPassword() : sipConfig.getPassword();
                    }
                    // 如果设置了一个无密码的设备，那么这里就会自动跳动，后续会直接注册成功
                }
            }else {
                if (ObjectUtils.isEmpty(sipConfig.getPassword())) {
                    log.info("{} 设备：{}, 地址: {}, 公共密码已经禁用，请添加用户信息后注册", title, deviceId, requestAddress);
                    response = getMessageFactory().createResponse(Response.FORBIDDEN, request);
                    sipSender.transmitRequest(request.getLocalAddress().getHostAddress(), response);
                    return;
                }else {
                    password = sipConfig.getPassword();
                }
            }

            // --- D6: 401 challenge handled HERE in caller, not in helper methods ---
            boolean needsAuth;
            if (identityEnabled) {
                boolean deviceHasHa1 = device != null && !ObjectUtils.isEmpty(device.getSipHa1());
                boolean deviceHasPassword = device != null && !ObjectUtils.isEmpty(device.getPassword());
                boolean hasGlobalPassword = !ObjectUtils.isEmpty(sipConfig.getPassword());
                needsAuth = deviceHasHa1 || deviceHasPassword || hasGlobalPassword;
            } else {
                needsAuth = !ObjectUtils.isEmpty(password);
            }

            AuthorizationHeader authHead = (AuthorizationHeader) request.getHeader(AuthorizationHeader.NAME);
            if (authHead == null && needsAuth) {
                log.info("{} 设备：{}, 回复401: {}", title, deviceId, requestAddress);
                response = getMessageFactory().createResponse(Response.UNAUTHORIZED, request);
                new DigestServerAuthenticationHelper().generateChallenge(getHeaderFactory(), response, sipConfig.getDomain());
                sipSender.transmitRequest(request.getLocalAddress().getHostAddress(), response);
                return;
            }

            // --- Authentication verification ---
            boolean passwordCorrect;
            if (identityEnabled) {
                passwordCorrect = authenticateWithStrategyChain(request, device);
            } else {
                passwordCorrect = authenticateWithLegacyPath(request, password);
            }

            if (!passwordCorrect) {
                // 注册失败
                response = getMessageFactory().createResponse(Response.FORBIDDEN, request);
                response.setReasonPhrase("wrong password");
                log.info("{} 设备：{}, 密码/SIP服务器ID错误, 回复403: {}", title, deviceId, requestAddress);
                sipSender.transmitRequest(request.getLocalAddress().getHostAddress(), response);
                return;
            }
```

**Step 3: Add helper methods**

Add these two package-private methods before `getRegisterOkResponse` (before line 258):

```java
    // D6: Pure verification — no 401 sending. Returns true if auth passes, false if rejected.
    // Package-private for test access (same package as test class).
    boolean authenticateWithStrategyChain(SIPRequest request, Device device)
            throws javax.sip.SipException, java.security.NoSuchAlgorithmException, java.text.ParseException {
        // 策略链认证
        if (device != null) {
            AuthResult result = strategyChain.authenticate(device, request);
            if (result == AuthResult.SUCCESS) {
                return true;
            }
            if (result == AuthResult.FAIL) {
                return false;
            }
            // SKIP — fall through
        }

        // 所有策略 SKIP → 全局密码兜底
        String globalPassword = sipConfig.getPassword();
        if (!ObjectUtils.isEmpty(globalPassword)) {
            return new DigestServerAuthenticationHelper()
                    .doAuthenticatePlainTextPassword(request, globalPassword);
        }

        // 设备发了 Authorization 但没有策略验证 → 拒绝（防止 SKIP 绕过）
        AuthorizationHeader authHead = (AuthorizationHeader) request.getHeader(AuthorizationHeader.NAME);
        if (authHead != null) {
            log.warn("authenticateWithStrategyChain: device sent auth but no strategy verified — rejecting");
            return false;
        }

        // 无需认证
        return true;
    }

    // D6: Pure verification — reproduces original behavior for legacy path.
    // Package-private for test access.
    boolean authenticateWithLegacyPath(SIPRequest request, String password)
            throws javax.sip.SipException, java.security.NoSuchAlgorithmException, java.text.ParseException {
        if (ObjectUtils.isEmpty(password)) {
            return true;
        }
        return new DigestServerAuthenticationHelper()
                .doAuthenticatePlainTextPassword(request, password);
    }
```

**Key design decisions in the refactor (updated for D2/D5/D6):**

1. **Lines 117–138 (re-registration fast path)** — completely preserved, no changes.
2. **Lines 116, 139–155 (password resolution + unknown device 403)** — preserved as-is, this block still resolves `password` for legacy fallback.
3. **Lines 157–164 (401 challenge)** — D6: extracted to caller (Step 2). Both paths share one 401 dispatch. No duplication.
4. **Lines 166–177 (password verification + 403)** — replaced with strategy-chain dispatch. Helper methods are pure verification.
5. **Kill switch** — `identityEnabled=false` calls `authenticateWithLegacyPath`, which reproduces the exact original behavior.
6. **D5: No `@Autowired DigestServerAuthenticationHelper`** — all uses create `new DigestServerAuthenticationHelper()` inline, preserving thread safety.
7. **D2: No `legacyPassword` parameter** — `authenticateWithStrategyChain` reads credentials directly from device and sipConfig.
8. **D6: `authHead != null` guard** — prevents SKIP bypass when device sent auth but no strategy verified it.

**Step 4: Verify compilation**

Run: `mvn compile -pl . -q`
Expected: BUILD SUCCESS.

**Step 5: Run existing tests**

Run: `mvn test -pl . -DskipTests=false`
Expected: All existing tests pass (strategy tests + W05 tests).

**Step 6: Commit**

```bash
git add src/main/java/com/genersoft/iot/vmp/gb28181/transmit/event/request/impl/RegisterRequestProcessor.java
git commit -m "feat(W04): refactor RegisterRequestProcessor — strategy chain behind feature flag"
```

---

## Task 7: Configuration Verification

**Files:**
- Verify: `src/main/java/com/genersoft/iot/vmp/jxt/identity/config/IdentityConfig.java` (already exists)
- Verify: `src/main/resources/application.yml`
- Verify: `docker/wvp/wvp/application-docker.yml`

> **D5 Note:** `DigestServerAuthenticationHelper` is NOT made a Spring bean. It stays as-is with `new` instantiation per call. No `@Component` annotation is added. Thread safety is maintained because each call gets its own instance.

**Step 1: Verify IdentityConfig**

The config class already exists at `src/main/java/com/genersoft/iot/vmp/jxt/identity/config/IdentityConfig.java` with:
- `jxt.identity.enabled` (default `true`)
- `jxt.identity.strategy.ha1-enabled` (default `true`)
- `jxt.identity.strategy.plaintext-enabled` (default `true`)

No changes needed — already implemented in W01+W05.

**Step 2: Verify application.yml**

`src/main/resources/application.yml` should NOT override any `jxt.identity.*` values (let defaults work). Current file only has `spring.profiles.active: 274-dev` and `sy.enable: true`. No changes needed.

**Step 3: Verify docker config**

`docker/wvp/wvp/application-docker.yml` should NOT override `jxt.identity.*` (defaults are fine). No changes needed.

**Step 4: Verify no changes needed to DigestServerAuthenticationHelper**

D5: `DigestServerAuthenticationHelper` remains as-is. No `@Component` annotation. No Spring bean registration. Strategies and helper methods create new instances via `new DigestServerAuthenticationHelper()` per call. This preserves thread safety.

Run: `grep -n "@Component" src/main/java/com/genersoft/iot/vmp/gb28181/auth/DigestServerAuthenticationHelper.java`

Expected: NOT found. If found, remove it (it should not be a bean).

---

## Task 8: RegisterRequestProcessor Integration Tests

**Files:**
- Create: `src/test/java/com/genersoft/iot/vmp/gb28181/transmit/event/request/impl/RegisterRequestProcessorAuthTest.java`

These tests verify the `authenticateWithStrategyChain` and `authenticateWithLegacyPath` helper methods directly. The helper methods are package-private (no `private` modifier) so the test class in the same package can call them.

**Step 1: Write integration tests**

```java
package com.genersoft.iot.vmp.gb28181.transmit.event.request.impl;

import com.genersoft.iot.vmp.gb28181.auth.DigestServerAuthenticationHelper;
import com.genersoft.iot.vmp.gb28181.bean.Device;
import com.genersoft.iot.vmp.jxt.identity.auth.*;
import gov.nist.javax.sip.message.SIPRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sip.header.AuthorizationHeader;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * D4: Integration tests for RegisterRequestProcessor helper methods.
 * Tests authenticateWithStrategyChain and authenticateWithLegacyPath directly.
 * Helper methods are package-private for test access.
 */
class RegisterRequestProcessorAuthTest {

    private RegisterRequestProcessor processor;
    private DeviceAuthStrategyChain chain;
    private DigestServerAuthenticationHelper digestHelper;
    private SipConfig sipConfig;

    @BeforeEach
    void setUp() {
        processor = new RegisterRequestProcessor();
        digestHelper = mock(DigestServerAuthenticationHelper.class);
        sipConfig = new SipConfig();
        sipConfig.setDomain("3402000000");
        sipConfig.setPassword(null);

        // Create strategies with mocked digest helper
        Ha1Strategy ha1 = new Ha1Strategy() {
            @Override DigestServerAuthenticationHelper createHelper() { return digestHelper; }
        };
        PlaintextStrategy plain = new PlaintextStrategy() {
            @Override DigestServerAuthenticationHelper createHelper() { return digestHelper; }
        };
        chain = new DeviceAuthStrategyChain(List.of(ha1, plain));

        ReflectionTestUtils.setField(processor, "strategyChain", chain);
        ReflectionTestUtils.setField(processor, "sipConfig", sipConfig);
    }

    // --- authenticateWithStrategyChain tests ---

    @Test
    void strategyChain_ha1Device_authSuccess() throws Exception {
        Device device = new Device();
        device.setDeviceId("35020000001320000075");
        device.setSipHa1("011b6698544b88130d7b626327ac15f8");

        SIPRequest request = mock(SIPRequest.class);
        AuthorizationHeader authHead = mock(AuthorizationHeader.class);
        when(request.getHeader(AuthorizationHeader.NAME)).thenReturn(authHead);
        when(digestHelper.doAuthenticateHashedPassword(any(), eq("011b6698544b88130d7b626327ac15f8")))
                .thenReturn(true);

        assertTrue(processor.authenticateWithStrategyChain(request, device));
    }

    @Test
    void strategyChain_ha1Device_authFail() throws Exception {
        Device device = new Device();
        device.setDeviceId("35020000001320000075");
        device.setSipHa1("011b6698544b88130d7b626327ac15f8");

        SIPRequest request = mock(SIPRequest.class);
        AuthorizationHeader authHead = mock(AuthorizationHeader.class);
        when(request.getHeader(AuthorizationHeader.NAME)).thenReturn(authHead);
        when(digestHelper.doAuthenticateHashedPassword(any(), eq("011b6698544b88130d7b626327ac15f8")))
                .thenReturn(false);

        assertFalse(processor.authenticateWithStrategyChain(request, device));
    }

    @Test
    void strategyChain_passwordDevice_plaintextSuccess() throws Exception {
        Device device = new Device();
        device.setDeviceId("34020000001320000001");
        device.setSipHa1(null);
        device.setPassword("camera123");

        SIPRequest request = mock(SIPRequest.class);
        AuthorizationHeader authHead = mock(AuthorizationHeader.class);
        when(request.getHeader(AuthorizationHeader.NAME)).thenReturn(authHead);
        when(digestHelper.doAuthenticatePlainTextPassword(any(), eq("camera123")))
                .thenReturn(true);

        assertTrue(processor.authenticateWithStrategyChain(request, device));
    }

    @Test
    void strategyChain_noCredentials_noGlobalPassword_noAuth() throws Exception {
        Device device = new Device();
        device.setDeviceId("34020000001320000001");
        device.setSipHa1(null);
        device.setPassword(null);

        SIPRequest request = mock(SIPRequest.class);
        // No Authorization header, no credentials → no auth needed
        when(request.getHeader(AuthorizationHeader.NAME)).thenReturn(null);

        assertTrue(processor.authenticateWithStrategyChain(request, device));
    }

    @Test
    void strategyChain_allSkip_globalPasswordFallback() throws Exception {
        Device device = new Device();
        device.setDeviceId("34020000001320000001");
        device.setSipHa1(null);
        device.setPassword(null);

        sipConfig.setPassword("global123");

        SIPRequest request = mock(SIPRequest.class);
        AuthorizationHeader authHead = mock(AuthorizationHeader.class);
        when(request.getHeader(AuthorizationHeader.NAME)).thenReturn(authHead);
        when(digestHelper.doAuthenticatePlainTextPassword(any(), eq("global123")))
                .thenReturn(true);

        assertTrue(processor.authenticateWithStrategyChain(request, device));
    }

    @Test
    void strategyChain_allSkip_globalPasswordFail() throws Exception {
        Device device = new Device();
        device.setDeviceId("34020000001320000001");
        device.setSipHa1(null);
        device.setPassword(null);

        sipConfig.setPassword("global123");

        SIPRequest request = mock(SIPRequest.class);
        AuthorizationHeader authHead = mock(AuthorizationHeader.class);
        when(request.getHeader(AuthorizationHeader.NAME)).thenReturn(authHead);
        when(digestHelper.doAuthenticatePlainTextPassword(any(), eq("global123")))
                .thenReturn(false);

        assertFalse(processor.authenticateWithStrategyChain(request, device));
    }

    @Test
    void strategyChain_authSentButNoStrategyVerified_rejects() throws Exception {
        // D6: device sent Authorization header but all strategies SKIP and no global password
        Device device = new Device();
        device.setDeviceId("34020000001320000001");
        device.setSipHa1(null);
        device.setPassword(null);

        SIPRequest request = mock(SIPRequest.class);
        AuthorizationHeader authHead = mock(AuthorizationHeader.class);
        when(request.getHeader(AuthorizationHeader.NAME)).thenReturn(authHead);

        assertFalse(processor.authenticateWithStrategyChain(request, device));
    }

    @Test
    void strategyChain_nullDevice_globalPasswordSuccess() throws Exception {
        sipConfig.setPassword("global123");

        SIPRequest request = mock(SIPRequest.class);
        AuthorizationHeader authHead = mock(AuthorizationHeader.class);
        when(request.getHeader(AuthorizationHeader.NAME)).thenReturn(authHead);
        when(digestHelper.doAuthenticatePlainTextPassword(any(), eq("global123")))
                .thenReturn(true);

        assertTrue(processor.authenticateWithStrategyChain(request, null));
    }

    @Test
    void strategyChain_nullDevice_noGlobalPassword_noAuth() throws Exception {
        SIPRequest request = mock(SIPRequest.class);
        when(request.getHeader(AuthorizationHeader.NAME)).thenReturn(null);

        assertTrue(processor.authenticateWithStrategyChain(request, null));
    }

    // --- authenticateWithLegacyPath tests ---

    @Test
    void legacyPath_noPassword_returnsTrue() throws Exception {
        SIPRequest request = mock(SIPRequest.class);
        assertTrue(processor.authenticateWithLegacyPath(request, null));
    }

    @Test
    void legacyPath_emptyPassword_returnsTrue() throws Exception {
        SIPRequest request = mock(SIPRequest.class);
        assertTrue(processor.authenticateWithLegacyPath(request, ""));
    }

    @Test
    void legacyPath_passwordCorrect() throws Exception {
        SIPRequest request = mock(SIPRequest.class);
        when(digestHelper.doAuthenticatePlainTextPassword(any(), eq("camera123")))
                .thenReturn(true);
        assertTrue(processor.authenticateWithLegacyPath(request, "camera123"));
    }

    @Test
    void legacyPath_passwordWrong() throws Exception {
        SIPRequest request = mock(SIPRequest.class);
        when(digestHelper.doAuthenticatePlainTextPassword(any(), eq("wrong")))
                .thenReturn(false);
        assertFalse(processor.authenticateWithLegacyPath(request, "wrong"));
    }
}
```

**Step 2: Run tests**

Run: `mvn test -pl . -Dtest="RegisterRequestProcessorAuthTest" -DskipTests=false`
Expected: PASS (13 tests)

**Step 3: Commit**

```bash
git add src/test/java/com/genersoft/iot/vmp/gb28181/transmit/event/request/impl/RegisterRequestProcessorAuthTest.java
git commit -m "test(W04): add RegisterRequestProcessor auth integration tests — 13 paths"
```

---

## Task 9: Full Build Verification

**Files:** No new files.

**Step 1: Clean compile**

Run: `mvn clean compile -pl .`
Expected: BUILD SUCCESS.

**Step 2: Run all tests**

Run: `mvn test -pl . -DskipTests=false`
Expected: All tests pass — W05 tests + W04 strategy tests + auth integration tests.

**Step 3: Full package**

Run: `mvn clean package -DskipTests`
Expected: BUILD SUCCESS. `target/wvp-pro-*.jar` created.

**Step 4: Commit (if any fixups were needed)**

```bash
git add -A
git commit -m "fix: address integration issues from W04 strategy chain"
```

---

## Task 10: Manual E2E Test — SIP REGISTER with HA1

**Prerequisites:**
- WVP running with `jxt.identity.enabled=true` (default)
- Device exists in `wvp_device` with `sip_ha1` populated by IAM (confirmed: 28 devices in DB)
- Real ZX terminal or SIP test client configured with matching credentials

**Step 1: Verify existing HA1 data in database**

```sql
SELECT device_id, sip_ha1, disabled, activated, on_line
FROM wvp_device
WHERE sip_ha1 IS NOT NULL
LIMIT 5;
```

Expected: Rows returned with 32-char hex `sip_ha1` values.

**Step 2: Test IAM-pushed device REGISTER**

1. IAM creates equipment and pushes register payload to WVP (already done).
2. Verify WVP DB has `sip_ha1` for device.
3. Terminal sends SIP REGISTER without Authorization.
4. WVP replies **401 challenge**.
5. Terminal sends SIP REGISTER with digest Authorization.
6. Ha1Strategy validates digest.
7. WVP replies **200 OK**.
8. Device becomes online.

**Step 3: Test legacy device REGISTER**

1. Find or create a device with `password` set but `sip_ha1 = NULL`.
2. Terminal sends REGISTER with correct digest.
3. PlaintextStrategy validates → **200 OK**.

**Step 4: Verify kill switch**

1. Set `jxt.identity.enabled=false` in config.
2. Restart WVP.
3. Legacy device with password still registers successfully.
4. IAM-pushed device with sipHa1 also works via global password (if configured).
5. Set back to `true`.

**Step 5: SQL verification after E2E**

```sql
SELECT device_id, sip_ha1, on_line, ip, port
FROM wvp_device
WHERE device_id = '{test-device-id}';
-- Expected: on_line=1, ip/port filled
```

---

## Task Summary

| Task | Action | Key File |
|------|--------|----------|
| 1 | Create | `jxt/identity/auth/AuthResult.java` + test |
| 2 | Create | `jxt/identity/auth/DeviceAuthStrategy.java` |
| 3 | Create | `jxt/identity/auth/Ha1Strategy.java` + test (5 tests, D5: `new` per call) |
| 4 | Create | `jxt/identity/auth/PlaintextStrategy.java` + test (6 tests, D5: `new` per call) |
| 5 | Create | `jxt/identity/auth/DeviceAuthStrategyChain.java` + test (5 tests) |
| 6 | Modify | `RegisterRequestProcessor.java` — D6: 401 in caller, D5: `new` helper, D2: no dead param |
| 7 | Verify | Config only — D5: no @Component on DigestServerAuthenticationHelper |
| 8 | Create | `RegisterRequestProcessorAuthTest.java` — D4: 13 integration tests |
| 9 | Verify | Full build + all tests pass |
| 10 | Manual | SIP REGISTER HA1 E2E with real terminal |

---

## Implementation Order

```
Tasks 1-2 (interfaces) → Task 3 (Ha1Strategy) → Task 4 (PlaintextStrategy) → Task 5 (Chain)
  ↓
Task 6 (RegisterRequestProcessor refactor)
  ↓
Task 7 (Config + Bean verification)
  ↓
Task 8 (Integration tests) → Task 9 (Full build)
  ↓
Task 10 (Manual E2E)
```

---

## Rollback

**Soft rollback** (zero code changes):
```yaml
jxt:
  identity:
    enabled: false
```
All REGISTER auth falls back to legacy password/global-password path. Strategy chain is not called.

**Hard rollback**: Revert Task 6 only (RegisterRequestProcessor refactor). Keep Tasks 1–5 (strategy classes exist but are unused). Keep W01+W05 schema and data intact. Task 7 is verification-only — no code to revert. DigestServerAuthenticationHelper is unchanged (D5: never became a bean).

---

## GSTACK REVIEW REPORT

| Review | Trigger | Why | Runs | Status | Findings |
|--------|---------|-----|------|--------|----------|
| CEO Review | `/plan-ceo-review` | Scope & strategy | 1 | issues_open | HOLD_SCOPE mode, 2 critical gaps |
| Codex Review | Codex outside voice | Independent 2nd opinion | 1 | issues_found | 4 P0, 5 P1 issues found |
| Eng Review | `/plan-eng-review` | Architecture & tests (required) | 2 | issues_open | 6 issues, 3 critical gaps |
| Outside Voice | Claude subagent | Independent challenge | 1 | issues_found | P0 thread safety bug |

**CODEX:** Found 2 additional P0 bugs missed by Claude review: (1) 401+403 double-send, (2) SKIP auth bypass. Both fixed by D6.

**CROSS-MODEL:** Both Claude subagent and Codex independently found the MessageDigest thread-safety bug (D5). Codex additionally found the 401+403 double-send (D6) and SKIP auth bypass — Claude review missed these.

**CRITICAL FINDINGS:**
1. **D5 (P0):** `DigestServerAuthenticationHelper` contains a `MessageDigest` field that is NOT thread-safe. Do NOT make it a `@Component` singleton. Keep `new` per call.
2. **D6 (P0):** Helper method sends 401 then returns `false`, caller sends 403 — device gets both responses. Fix: extract 401 challenge to caller, helpers only do verification.
3. **D4 (P1):** Task 8 integration tests are meaningless. Must rewrite with proper coverage.

**RESOLVED DECISIONS:**
- D1: FAIL stops chain (security-first) ✓
- D2: Remove dead `legacyPassword` parameter from `authenticateWithStrategyChain` ✓
- D3: 401 duplication — OVERRIDDEN by D6 (extract to caller, eliminating duplication entirely) ✓
- D4: Rewrite Task 8 integration tests ✓
- D5: Keep `new DigestServerAuthenticationHelper()` per call (thread safety) ✓
- D6: Extract 401 challenge to caller (fixes 401+403 double-send + SKIP auth bypass) ✓

**UNRESOLVED:** 0

**VERDICT:** ENG REVIEW — 3 critical gaps addressed (D5 thread safety + D6 401 flow + D4 tests). Plan is implementable after applying all decisions.
