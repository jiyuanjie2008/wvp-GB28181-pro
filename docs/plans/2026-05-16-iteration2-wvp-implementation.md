# Iteration 2 WVP Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Implement WVP-side SIP authentication overhaul (strategy chain) and IAM credential sync endpoint for the unified terminal identity system.

**Architecture:** New `jxt.identity` package under `com.genersoft.iot.vmp.jxt.identity` with auth strategy chain (Ha1Strategy + PlaintextStrategy) and DeviceIdentityController (POST /api/sy/device register). Device table extended with `sip_ha1` columns. RegisterRequestProcessor refactored to delegate to strategy chain.

**Tech Stack:** Java 21, Spring Boot 3.4.4, MyBatis (annotation-based, no XML), JAIN-SIP, Hutool (SM3/SM4 in existing SignAuthenticationFilter), MySQL 8, Lombok

---

## Task 1: SQL Migration Script

**Files:**
- Create: `数据库/2.7.4/更新-mysql-2.7.4-jxt-device-identity.sql`

**Step 1: Write the migration script**

```sql
-- ============================================================
-- WVP 迭代 2: 设备身份 + 凭证同步
-- Date: 2026-05-16
-- Based on: 2026-05-16-iteration2-wvp-design.md §3
-- ============================================================

DELIMITER //

-- 1. Device 表新增列
CREATE PROCEDURE `wvp_device_identity_columns`()
BEGIN
    -- sip_ha1: HA1 摘要
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE TABLE_SCHEMA = (SELECT DATABASE()) AND table_name = 'wvp_device' AND column_name = 'sip_ha1') THEN
        ALTER TABLE wvp_device ADD COLUMN sip_ha1 VARCHAR(64) DEFAULT NULL COMMENT 'HA1摘要 = MD5(deviceId:realm:password)';
    END IF;

    -- sip_ha1_previous: 轮换双发窗口旧 HA1
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE TABLE_SCHEMA = (SELECT DATABASE()) AND table_name = 'wvp_device' AND column_name = 'sip_ha1_previous') THEN
        ALTER TABLE wvp_device ADD COLUMN sip_ha1_previous VARCHAR(64) DEFAULT NULL COMMENT '轮换双发窗口：旧HA1';
    END IF;

    -- previous_valid_until: 旧 HA1 过期时间
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE TABLE_SCHEMA = (SELECT DATABASE()) AND table_name = 'wvp_device' AND column_name = 'previous_valid_until') THEN
        ALTER TABLE wvp_device ADD COLUMN previous_valid_until DATETIME DEFAULT NULL COMMENT '旧HA1过期时间';
    END IF;

    -- disabled: 设备禁用标记
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE TABLE_SCHEMA = (SELECT DATABASE()) AND table_name = 'wvp_device' AND column_name = 'disabled') THEN
        ALTER TABLE wvp_device ADD COLUMN disabled BOOLEAN DEFAULT FALSE COMMENT '设备禁用标记';
    END IF;

    -- activated: 激活标记
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE TABLE_SCHEMA = (SELECT DATABASE()) AND table_name = 'wvp_device' AND column_name = 'activated') THEN
        ALTER TABLE wvp_device ADD COLUMN activated BOOLEAN DEFAULT TRUE COMMENT '激活标记';
    END IF;
END; //

CALL wvp_device_identity_columns();
DROP PROCEDURE wvp_device_identity_columns;

-- 2. 索引
CREATE PROCEDURE `wvp_device_identity_indexes`()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                   WHERE TABLE_SCHEMA = (SELECT DATABASE()) AND table_name = 'wvp_device' AND index_name = 'idx_wvp_device_disabled') THEN
        CREATE INDEX idx_wvp_device_disabled ON wvp_device(disabled);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                   WHERE TABLE_SCHEMA = (SELECT DATABASE()) AND table_name = 'wvp_device' AND index_name = 'idx_wvp_device_prev_expiry_cover') THEN
        CREATE INDEX idx_wvp_device_prev_expiry_cover ON wvp_device(previous_valid_until, sip_ha1_previous);
    END IF;
END; //

CALL wvp_device_identity_indexes();
DROP PROCEDURE wvp_device_identity_indexes;

-- 3. wvp_idempotency_log（幂等日志）
CREATE TABLE IF NOT EXISTS wvp_idempotency_log (
    idempotency_key VARCHAR(128) PRIMARY KEY,
    operation       VARCHAR(32) NOT NULL,
    device_id       VARCHAR(50) NOT NULL,
    status          VARCHAR(16) NOT NULL DEFAULT 'success',
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 4. wvp_revocation_task（迭代 3 使用，schema 先建）
CREATE TABLE IF NOT EXISTS wvp_revocation_task (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    device_id       VARCHAR(50) NOT NULL COMMENT '设备国标ID',
    task_type       VARCHAR(32) NOT NULL DEFAULT 'revoke' COMMENT '任务类型',
    status          VARCHAR(16) NOT NULL DEFAULT 'pending' COMMENT 'pending/running/completed/failed',
    attempts        INT NOT NULL DEFAULT 0,
    max_attempts    INT NOT NULL DEFAULT 3,
    last_error      TEXT,
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at    DATETIME,
    started_at      DATETIME,
    CONSTRAINT uq_revocation_task_pending UNIQUE (device_id, task_type, status)
);

CREATE INDEX idx_revocation_task_status ON wvp_revocation_task(status, created_at);

-- 5. wvp_realm_transition（迭代 3 使用，schema 先建）
CREATE TABLE IF NOT EXISTS wvp_realm_transition (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    device_id       VARCHAR(50) NOT NULL COMMENT '设备国标ID',
    old_realm       VARCHAR(64) NOT NULL,
    new_realm       VARCHAR(64) NOT NULL,
    valid_until     DATETIME NOT NULL COMMENT '旧realm fallback截止时间',
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_realm_transition_device ON wvp_realm_transition(device_id, valid_until);

DELIMITER ;
```

**Step 2: Verify script syntax**

Run: `mysql -u root -p < 数据库/2.7.4/更新-mysql-2.7.4-jxt-device-identity.sql`
Expected: All procedures execute, tables/columns created. Re-running is idempotent (IF NOT EXISTS guards).

**Step 3: Commit**

```bash
git add 数据库/2.7.4/更新-mysql-2.7.4-jxt-device-identity.sql
git commit -m "feat(W01): SQL migration — Device identity columns + idempotency/revocation/realm tables"
```

---

## Task 2: Device.java Entity Fields

**Files:**
- Modify: `src/main/java/com/genersoft/iot/vmp/gb28181/bean/Device.java:193-214` (after `geoCoordSys`, before `password`)

**Step 1: Add new fields to Device.java**

Insert after line 193 (`private String geoCoordSys;`), before line 195 (`@Schema(description = "密码")`):

```java
	@Schema(description = "HA1摘要 = MD5(deviceId:realm:password)")
	private String sipHa1;

	@Schema(description = "轮换双发窗口：旧HA1")
	private String sipHa1Previous;

	@Schema(description = "旧HA1过期时间")
	private java.util.Date previousValidUntil;

	@Schema(description = "设备禁用标记")
	private Boolean disabled = false;

	@Schema(description = "激活标记")
	private Boolean activated = true;
```

**Step 2: Verify compilation**

Run: `mvn compile -pl . -q`
Expected: BUILD SUCCESS. Lombok `@Data` on Device auto-generates getters/setters.

**Step 3: Commit**

```bash
git add src/main/java/com/genersoft/iot/vmp/gb28181/bean/Device.java
git commit -m "feat(W01): Add sip_ha1, disabled, activated fields to Device entity"
```

---

## Task 3: DeviceMapper.java — Add Fields to All Methods

**Files:**
- Modify: `src/main/java/com/genersoft/iot/vmp/gb28181/dao/DeviceMapper.java`

This is the most delicate task. Every method that reads or writes `wvp_device` must include the new columns. The project uses MyBatis annotation style (no XML).

**Step 1: Add columns to `getDeviceByDeviceId` (line 17-49)**

Add after `"broadcast_push_after_ack," +` (line 46) in the SELECT list:

```java
"sip_ha1," +
"sip_ha1_previous," +
"previous_valid_until," +
"disabled," +
"activated," +
```

**Step 2: Add columns to `add` (line 51-115)**

In the column list (after `"on_line"+` at line 81), add:

```java
",sip_ha1" +
",sip_ha1_previous" +
",previous_valid_until" +
",disabled" +
",activated" +
```

In the values list (after `"#{onLine}" +` at line 112), add:

```java
",#{sipHa1}" +
",#{sipHa1Previous}" +
",#{previousValidUntil}" +
",#{disabled}" +
",#{activated}" +
```

**Step 3: Add columns to `update` (line 117-141)**

Add after `", expires=#{expires}" +` (line 137), before `", server_id=#{serverId}" +` (line 138):

```java
", sip_ha1=#{sipHa1}" +
", sip_ha1_previous=#{sipHa1Previous}" +
", previous_valid_until=#{previousValidUntil}" +
", disabled=#{disabled}" +
", activated=#{activated}" +
```

**Step 4: Add columns to `getDevices` (line 143-180)**

Add to the SELECT list after `"broadcast_push_after_ack,"+` (line ~170):

```java
"sip_ha1,"+
"sip_ha1_previous,"+
"previous_valid_until,"+
"disabled,"+
"activated,"+
```

**Step 5: Add columns to `updateCustom` (line 281-289)**

Add after `", geo_coord_sys=#{geoCoordSys}, media_server_id=#{mediaServerId}" +` (line 286):

```java
", sip_ha1=#{sipHa1}" +
", sip_ha1_previous=#{sipHa1Previous}" +
", previous_valid_until=#{previousValidUntil}" +
", disabled=#{disabled}" +
", activated=#{activated}" +
```

**Step 6: Add columns to `addCustomDevice` (line 291-324)**

In the column list (after `"media_server_id"+` at line 306), add:

```java
",sip_ha1" +
",sip_ha1_previous" +
",previous_valid_until" +
",disabled" +
",activated" +
```

In the values list (after `"#{mediaServerId}" +` at line 322), add:

```java
",#{sipHa1}" +
",#{sipHa1Previous}" +
",#{previousValidUntil}" +
",#{disabled}" +
",#{activated}" +
```

**Step 7: Add columns to `getOnlineDevices` (line 185-215)**

Add to the SELECT list after `"broadcast_push_after_ack,"+`:

```java
"sip_ha1,"+
"sip_ha1_previous,"+
"previous_valid_until,"+
"disabled,"+
"activated,"+
```

**Step 8: Add columns to `getOnlineDevicesByServerId` (line 217-248)**

Same pattern as Step 7.

**Step 9: Add columns to `getDeviceList` (line 332-377)**

Same pattern — add the 5 columns to the SELECT list.

**Step 9b: Scan ALL remaining methods**

The methods listed above (Steps 1-9) are the confirmed ones. However, **any future additions to DeviceMapper.java that reference `wvp_device` must also include the 5 new columns**. Before closing Task 3, search for all `@Select`/`@Insert`/`@Update` annotations touching `wvp_device` and verify none were missed:

```bash
grep -n "wvp_device" src/main/java/com/genersoft/iot/vmp/gb28181/dao/DeviceMapper.java
```

Pay special attention to `batchUpdate()` (around line 414) — it updates all fields in a foreach loop.

**Step 10: Verify compilation**

Run: `mvn compile -pl . -q`
Expected: BUILD SUCCESS. All SELECT/INSERT/UPDATE annotations compile. At runtime, existing rows will have `null` for `sip_ha1` columns and `false`/`true` defaults for `disabled`/`activated`.

**Step 11: Commit**

```bash
git add src/main/java/com/genersoft/iot/vmp/gb28181/dao/DeviceMapper.java
git commit -m "feat(W01): Add sip_ha1 columns to all DeviceMapper annotation SQL"
```

---

## Task 4: Auth Strategy Interface + AuthResult Enum

**Files:**
- Create: `src/main/java/com/genersoft/iot/vmp/jxt/identity/auth/DeviceAuthStrategy.java`
- Create: `src/main/java/com/genersoft/iot/vmp/jxt/identity/auth/AuthResult.java`

**Step 1: Create AuthResult enum**

```java
package com.genersoft.iot.vmp.jxt.identity.auth;

public enum AuthResult {
    SUCCESS,
    FAIL,
    SKIP
}
```

**Step 2: Create DeviceAuthStrategy interface**

```java
package com.genersoft.iot.vmp.jxt.identity.auth;

import com.genersoft.iot.vmp.gb28181.bean.Device;

import javax.sip.message.Request;

public interface DeviceAuthStrategy {
    int priority();
    AuthResult authenticate(Device device, Request sipRequest, String realm);
}
```

**Step 3: Verify compilation**

Run: `mvn compile -pl . -q`
Expected: BUILD SUCCESS.

**Step 4: Commit**

```bash
git add src/main/java/com/genersoft/iot/vmp/jxt/identity/auth/
git commit -m "feat(W04): Define DeviceAuthStrategy interface and AuthResult enum"
```

---

## Task 5: Ha1Strategy Implementation

**Files:**
- Create: `src/main/java/com/genersoft/iot/vmp/jxt/identity/auth/Ha1Strategy.java`

**Reference:** `DigestServerAuthenticationHelper.java:116` — `doAuthenticateHashedPassword(Request request, String hashedPassword)`

**Step 1: Create Ha1Strategy**

```java
package com.genersoft.iot.vmp.jxt.identity.auth;

import com.genersoft.iot.vmp.gb28181.auth.DigestServerAuthenticationHelper;
import com.genersoft.iot.vmp.gb28181.bean.Device;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.ObjectUtils;

import javax.sip.message.Request;

@Slf4j
@Component
@ConditionalOnProperty(value = "jxt.identity.strategy.ha1-enabled", havingValue = "true", matchIfMissing = true)
public class Ha1Strategy implements DeviceAuthStrategy {

    private final DigestServerAuthenticationHelper digestHelper = new DigestServerAuthenticationHelper();

    @Override
    public int priority() {
        return 1;
    }

    @Override
    public AuthResult authenticate(Device device, Request sipRequest, String realm) {
        if (ObjectUtils.isEmpty(device.getSipHa1())) {
            return AuthResult.SKIP;
        }

        try {
            boolean ok = digestHelper.doAuthenticateHashedPassword(sipRequest, device.getSipHa1());
            if (ok) {
                log.debug("Ha1Strategy: device {} authenticated via HA1 digest", device.getDeviceId());
                return AuthResult.SUCCESS;
            } else {
                log.warn("Ha1Strategy: device {} HA1 digest verification failed", device.getDeviceId());
                return AuthResult.FAIL;
            }
        } catch (Exception e) {
            log.error("Ha1Strategy: error authenticating device {}", device.getDeviceId(), e);
            return AuthResult.FAIL;
        }
    }
}
```

**Step 2: Verify compilation**

Run: `mvn compile -pl . -q`
Expected: BUILD SUCCESS.

**Step 3: Commit**

```bash
git add src/main/java/com/genersoft/iot/vmp/jxt/identity/auth/Ha1Strategy.java
git commit -m "feat(W04): Implement Ha1Strategy — SIP digest auth with pre-computed HA1"
```

---

## Task 6: PlaintextStrategy Implementation

**Files:**
- Create: `src/main/java/com/genersoft/iot/vmp/jxt/identity/auth/PlaintextStrategy.java`

**Reference:** `DigestServerAuthenticationHelper.java:184` — `doAuthenticatePlainTextPassword(Request request, String pass)`

**Step 1: Create PlaintextStrategy**

```java
package com.genersoft.iot.vmp.jxt.identity.auth;

import com.genersoft.iot.vmp.gb28181.auth.DigestServerAuthenticationHelper;
import com.genersoft.iot.vmp.gb28181.bean.Device;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.ObjectUtils;

import javax.sip.message.Request;

@Slf4j
@Component
@ConditionalOnProperty(value = "jxt.identity.strategy.plaintext-enabled", havingValue = "true", matchIfMissing = true)
public class PlaintextStrategy implements DeviceAuthStrategy {

    private final DigestServerAuthenticationHelper digestHelper = new DigestServerAuthenticationHelper();

    @Override
    public int priority() {
        return 2;
    }

    @Override
    public AuthResult authenticate(Device device, Request sipRequest, String realm) {
        String password = device.getPassword();
        if (ObjectUtils.isEmpty(password)) {
            return AuthResult.SKIP;
        }

        try {
            boolean ok = digestHelper.doAuthenticatePlainTextPassword(sipRequest, password);
            if (ok) {
                log.debug("PlaintextStrategy: device {} authenticated via plaintext password", device.getDeviceId());
                return AuthResult.SUCCESS;
            } else {
                log.warn("PlaintextStrategy: device {} password verification failed", device.getDeviceId());
                return AuthResult.FAIL;
            }
        } catch (Exception e) {
            log.error("PlaintextStrategy: error authenticating device {}", device.getDeviceId(), e);
            return AuthResult.FAIL;
        }
    }
}
```

**Step 2: Verify compilation**

Run: `mvn compile -pl . -q`
Expected: BUILD SUCCESS.

**Step 3: Commit**

```bash
git add src/main/java/com/genersoft/iot/vmp/jxt/identity/auth/PlaintextStrategy.java
git commit -m "feat(W04): Implement PlaintextStrategy — legacy device password fallback"
```

---

## Task 7: DeviceAuthStrategyChain

**Files:**
- Create: `src/main/java/com/genersoft/iot/vmp/jxt/identity/auth/DeviceAuthStrategyChain.java`

**Step 1: Create strategy chain**

```java
package com.genersoft.iot.vmp.jxt.identity.auth;

import com.genersoft.iot.vmp.gb28181.bean.Device;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.sip.message.Request;
import java.util.Comparator;
import java.util.List;

@Slf4j
@Component
@ConditionalOnProperty(value = "jxt.identity.enabled", havingValue = "true", matchIfMissing = true)
public class DeviceAuthStrategyChain {

    private final List<DeviceAuthStrategy> strategies;

    public DeviceAuthStrategyChain(List<DeviceAuthStrategy> strategies) {
        this.strategies = strategies.stream()
                .sorted(Comparator.comparingInt(DeviceAuthStrategy::priority))
                .toList();
        log.info("DeviceAuthStrategyChain initialized with {} strategies: {}", strategies.size(),
                strategies.stream().map(s -> s.getClass().getSimpleName()).toList());
    }

    public AuthResult authenticate(Device device, Request sipRequest, String realm) {
        for (DeviceAuthStrategy strategy : strategies) {
            AuthResult result = strategy.authenticate(device, sipRequest, realm);
            if (result != AuthResult.SKIP) {
                return result;
            }
        }
        return AuthResult.SKIP;
    }
}
```

**Step 2: Verify compilation**

Run: `mvn compile -pl . -q`
Expected: BUILD SUCCESS.

**Step 3: Commit**

```bash
git add src/main/java/com/genersoft/iot/vmp/jxt/identity/auth/DeviceAuthStrategyChain.java
git commit -m "feat(W04): Implement DeviceAuthStrategyChain — ordered strategy dispatcher"
```

---

## Task 8: RegisterRequestProcessor Refactor

**Files:**
- Modify: `src/main/java/com/genersoft/iot/vmp/gb28181/transmit/event/request/impl/RegisterRequestProcessor.java`

This is the critical integration point. The existing auth logic at lines 116-177 must be replaced with strategy chain delegation.

**Step 1: Add autowired field for strategy chain**

After the existing `@Autowired` fields (around line 70), add:

```java
@Autowired
private DeviceAuthStrategyChain strategyChain;
```

Add import:
```java
import com.genersoft.iot.vmp.jxt.identity.auth.AuthResult;
import com.genersoft.iot.vmp.jxt.identity.auth.DeviceAuthStrategyChain;
```

**Step 2: Replace authentication logic**

Replace lines 116-177 (the password resolution + auth logic) with the strategy chain approach.

**BEFORE** (lines 116-177):
```java
            String password = null;
            if (device != null) {
                if (device.getSipTransactionInfo() != null &&
                        request.getCallIdHeader().getCallId().equals(device.getSipTransactionInfo().getCallId())) {
                    // ... re-registration fast path (lines 117-138) ... UNCHANGED
                    return;
                }else {
                    if (!ObjectUtils.isEmpty(device.getPassword()) || !ObjectUtils.isEmpty(sipConfig.getPassword())) {
                        password = (!ObjectUtils.isEmpty(device.getPassword())) ? device.getPassword() : sipConfig.getPassword();
                    }
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

            AuthorizationHeader authHead = (AuthorizationHeader) request.getHeader(AuthorizationHeader.NAME);
            if (authHead == null && !ObjectUtils.isEmpty(password)) {
                log.info(title + " 设备：{}, 回复401: {}", deviceId, requestAddress);
                response = getMessageFactory().createResponse(Response.UNAUTHORIZED, request);
                new DigestServerAuthenticationHelper().generateChallenge(getHeaderFactory(), response, sipConfig.getDomain());
                sipSender.transmitRequest(request.getLocalAddress().getHostAddress(), response);
                return;
            }

            passwordCorrect = ObjectUtils.isEmpty(password) ||
                    new DigestServerAuthenticationHelper().doAuthenticatePlainTextPassword(request, password);
            if (!passwordCorrect) {
                response = getMessageFactory().createResponse(Response.FORBIDDEN, request);
                response.setReasonPhrase("wrong password");
                log.info("{} 设备：{}, 密码/SIP服务器ID错误, 回复403: {}", title, deviceId, requestAddress);
                sipSender.transmitRequest(request.getLocalAddress().getHostAddress(), response);
                return;
            }
```

**AFTER** (new strategy chain logic):

```java
            if (device != null) {
                // Determine if device needs auth at all
                boolean deviceHasHa1 = !ObjectUtils.isEmpty(device.getSipHa1());
                boolean deviceHasPassword = !ObjectUtils.isEmpty(device.getPassword());
                String globalPassword = sipConfig.getPassword();
                boolean hasGlobalPassword = !ObjectUtils.isEmpty(globalPassword);
                boolean needsAuth = deviceHasHa1 || deviceHasPassword || hasGlobalPassword;

                // If device needs auth but no Authorization header → send 401 challenge
                AuthorizationHeader authHead = (AuthorizationHeader) request.getHeader(AuthorizationHeader.NAME);
                if (authHead == null && needsAuth) {
                    log.info(title + " 设备：{}, 回复401: {}", deviceId, requestAddress);
                    response = getMessageFactory().createResponse(Response.UNAUTHORIZED, request);
                    new DigestServerAuthenticationHelper().generateChallenge(getHeaderFactory(), response, sipConfig.getDomain());
                    sipSender.transmitRequest(request.getLocalAddress().getHostAddress(), response);
                    return;
                }

                if (!needsAuth) {
                    // No-auth mode: device has no HA1, no password, no global password → allow
                    passwordCorrect = true;
                } else {
                    // Run strategy chain
                    AuthResult result = strategyChain.authenticate(device, request, sipConfig.getDomain());

                    if (result == AuthResult.SUCCESS) {
                        passwordCorrect = true;
                    } else if (result == AuthResult.SKIP) {
                        // No strategy matched (no HA1, no device password) → try global password
                        passwordCorrect = new DigestServerAuthenticationHelper().doAuthenticatePlainTextPassword(request, globalPassword);
                    } else {
                        // FAIL: a strategy matched but verification failed
                        passwordCorrect = false;
                    }
                }
            } else {
                // Device not in DB: use global password or reject
                if (ObjectUtils.isEmpty(sipConfig.getPassword())) {
                    log.info("{} 设备：{}, 地址: {}, 公共密码已经禁用，请添加用户信息后注册", title, deviceId, requestAddress);
                    response = getMessageFactory().createResponse(Response.FORBIDDEN, request);
                    sipSender.transmitRequest(request.getLocalAddress().getHostAddress(), response);
                    return;
                } else {
                    AuthorizationHeader authHead = (AuthorizationHeader) request.getHeader(AuthorizationHeader.NAME);
                    if (authHead == null) {
                        log.info(title + " 设备：{}, 回复401: {}", deviceId, requestAddress);
                        response = getMessageFactory().createResponse(Response.UNAUTHORIZED, request);
                        new DigestServerAuthenticationHelper().generateChallenge(getHeaderFactory(), response, sipConfig.getDomain());
                        sipSender.transmitRequest(request.getLocalAddress().getHostAddress(), response);
                        return;
                    }
                    passwordCorrect = new DigestServerAuthenticationHelper().doAuthenticatePlainTextPassword(request, sipConfig.getPassword());
                }
            }

            if (!passwordCorrect) {
                response = getMessageFactory().createResponse(Response.FORBIDDEN, request);
                response.setReasonPhrase("wrong password");
                log.info("{} 设备：{}, 密码/SIP服务器ID错误, 回复403: {}", title, deviceId, requestAddress);
                sipSender.transmitRequest(request.getLocalAddress().getHostAddress(), response);
                return;
            }
```

**IMPORTANT**: Lines 116-138 (the re-registration fast-path with `CallId` matching) remain **unchanged**. Only the auth logic within the `else` block at line 139 and the unknown-device block at lines 146-155 are replaced.

**Step 3: Verify compilation**

Run: `mvn compile -pl . -q`
Expected: BUILD SUCCESS.

**Step 4: Commit**

```bash
git add src/main/java/com/genersoft/iot/vmp/gb28181/transmit/event/request/impl/RegisterRequestProcessor.java
git commit -m "feat(W04): Refactor RegisterRequestProcessor to use strategy chain for SIP auth"
```

---

## Task 9: IAM Sync Request DTOs

**Files:**
- Create: `src/main/java/com/genersoft/iot/vmp/jxt/identity/dto/IamSyncRequest.java`
- Create: `src/main/java/com/genersoft/iot/vmp/jxt/identity/dto/IamSyncPayloadSpecific.java`

**Step 1: Create IamSyncPayloadSpecific**

```java
package com.genersoft.iot.vmp.jxt.identity.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class IamSyncPayloadSpecific {
    private String deviceName;
    private String sipHa1;
    private String realm;
    private String charset;
    private String streamMode;
    private String sdpIp;
    private String mediaServerId;
    private Boolean ssrcCheck;
    private String geoCoordSys;
    private Boolean asMessageChannel;
    private Boolean broadcastPushAfterAck;
    private Integer heartbeatInterval;
    private Integer heartbeatCount;
}
```

**Step 2: Create IamSyncRequest**

```java
package com.genersoft.iot.vmp.jxt.identity.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class IamSyncRequest {
    private int schemaVersion;
    private String idempotencyKey;
    private String traceId;
    private int tenantId;
    private String targetDeviceId;
    private String operation;
    private String occurredAt;
    private IamSyncPayloadSpecific payloadSpecific;
}
```

**Step 3: Verify compilation**

Run: `mvn compile -pl . -q`
Expected: BUILD SUCCESS.

**Step 4: Commit**

```bash
git add src/main/java/com/genersoft/iot/vmp/jxt/identity/dto/
git commit -m "feat(W05): Add IAM sync request DTOs (IamSyncRequest, IamSyncPayloadSpecific)"
```

---

## Task 10: DeviceIdentityMapper (MyBatis)

**Files:**
- Create: `src/main/java/com/genersoft/iot/vmp/jxt/identity/mapper/DeviceIdentityMapper.java`

**Pattern reference:** `CallbackEventMapper.java` in `jxt/callback/`

**Step 1: Create DeviceIdentityMapper**

```java
package com.genersoft.iot.vmp.jxt.identity.mapper;

import com.genersoft.iot.vmp.gb28181.bean.Device;
import org.apache.ibatis.annotations.*;

@Mapper
public interface DeviceIdentityMapper {

    @Insert("INSERT INTO wvp_device (" +
            "device_id, name, sip_ha1, transport, charset, media_server_id, " +
            "ssrc_check, geo_coord_sys, as_message_channel, broadcast_push_after_ack, " +
            "heartbeat_interval, heartbeat_count, disabled, activated, password, expires, " +
            "create_time, update_time, on_line, stream_mode, sdp_ip, server_id" +
            ") VALUES (" +
            "#{deviceId}, #{name}, #{sipHa1}, #{transport}, #{charset}, #{mediaServerId}, " +
            "#{ssrcCheck}, #{geoCoordSys}, #{asMessageChannel}, #{broadcastPushAfterAck}, " +
            "#{heartBeatInterval}, #{heartBeatCount}, #{disabled}, #{activated}, #{password}, #{expires}, " +
            "#{createTime}, #{updateTime}, #{onLine}, #{streamMode}, #{sdpIp}, #{serverId}" +
            ")")
    int insertDevice(Device device);

    @Update({"<script>",
            "UPDATE wvp_device SET update_time=#{updateTime}, sip_ha1=#{sipHa1}",
            "<if test='name != null'>, name=#{name}</if>",
            "<if test='charset != null'>, charset=#{charset}</if>",
            "<if test='mediaServerId != null'>, media_server_id=#{mediaServerId}</if>",
            "<if test='transport != null'>, transport=#{transport}</if>",
            "<if test='ssrcCheck != null'>, ssrc_check=#{ssrcCheck}</if>",
            "<if test='geoCoordSys != null'>, geo_coord_sys=#{geoCoordSys}</if>",
            "<if test='asMessageChannel != null'>, as_message_channel=#{asMessageChannel}</if>",
            "<if test='broadcastPushAfterAck != null'>, broadcast_push_after_ack=#{broadcastPushAfterAck}</if>",
            "<if test='heartBeatInterval != null'>, heartbeat_interval=#{heartBeatInterval}</if>",
            "<if test='heartBeatCount != null'>, heartbeat_count=#{heartBeatCount}</if>",
            "<if test='disabled != null'>, disabled=#{disabled}</if>",
            "<if test='activated != null'>, activated=#{activated}</if>",
            "<if test='streamMode != null'>, stream_mode=#{streamMode}</if>",
            "<if test='sdpIp != null'>, sdp_ip=#{sdpIp}</if>",
            " WHERE device_id=#{deviceId}",
            "</script>"})
    int updateDevice(Device device);

    // --- Idempotency log ---

    @Insert("INSERT INTO wvp_idempotency_log (idempotency_key, operation, device_id, status) " +
            "VALUES (#{idempotencyKey}, #{operation}, #{deviceId}, #{status})")
    int insertIdempotencyLog(@Param("idempotencyKey") String key,
                             @Param("operation") String operation,
                             @Param("deviceId") String deviceId,
                             @Param("status") String status);

    @Select("SELECT status FROM wvp_idempotency_log WHERE idempotency_key = #{key}")
    String findIdempotencyStatus(@Param("key") String key);

    @Select("SELECT device_id FROM wvp_idempotency_log WHERE idempotency_key = #{key}")
    String findIdempotencyDeviceId(@Param("key") String key);

    @Update("UPDATE wvp_idempotency_log SET status = #{status} WHERE idempotency_key = #{key}")
    int updateIdempotencyStatus(@Param("key") String key, @Param("status") String status);

    @Delete("DELETE FROM wvp_idempotency_log WHERE idempotency_key = #{key}")
    int deleteIdempotencyLog(@Param("key") String key);

    @Delete("DELETE FROM wvp_idempotency_log WHERE created_at < DATE_SUB(NOW(), INTERVAL #{days} DAY)")
    int cleanOldEntries(@Param("days") int days);
}
```

**Step 2: Verify compilation**

Run: `mvn compile -pl . -q`
Expected: BUILD SUCCESS.

**Step 3: Commit**

```bash
git add src/main/java/com/genersoft/iot/vmp/jxt/identity/mapper/DeviceIdentityMapper.java
git commit -m "feat(W05): Add DeviceIdentityMapper — device insert/update + idempotency log"
```

---

## Task 11: DeviceIdentityService

**Files:**
- Create: `src/main/java/com/genersoft/iot/vmp/jxt/identity/service/DeviceIdentityService.java`

**Step 1: Create DeviceIdentityService**

```java
package com.genersoft.iot.vmp.jxt.identity.service;

import com.genersoft.iot.vmp.conf.SipConfig;
import com.genersoft.iot.vmp.gb28181.bean.Device;
import com.genersoft.iot.vmp.gb28181.dao.DeviceMapper;
import com.genersoft.iot.vmp.jxt.identity.dto.IamSyncRequest;
import com.genersoft.iot.vmp.jxt.identity.dto.IamSyncPayloadSpecific;
import com.genersoft.iot.vmp.jxt.identity.mapper.DeviceIdentityMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Slf4j
@Service
public class DeviceIdentityService {

    @Autowired
    private DeviceIdentityMapper identityMapper;

    @Autowired
    private DeviceMapper deviceMapper;

    @Autowired
    private SipConfig sipConfig;

    @Transactional
    public DeviceIdentityResult register(IamSyncRequest request) {
        String deviceId = request.getTargetDeviceId();
        IamSyncPayloadSpecific payload = request.getPayloadSpecific();
        String key = request.getIdempotencyKey();

        // Idempotency: INSERT (status='processing') to claim
        try {
            identityMapper.insertIdempotencyLog(key, request.getOperation(), deviceId, "processing");
        } catch (DuplicateKeyException e) {
            // Key already exists — check status
            String existingStatus = identityMapper.findIdempotencyStatus(key);
            if ("success".equals(existingStatus)) {
                log.info("Idempotent hit (success): key={}, device={}", key, deviceId);
                return DeviceIdentityResult.ok(deviceId, false);
            } else if ("processing".equals(existingStatus)) {
                log.info("Idempotent hit (processing): key={}", key);
                return DeviceIdentityResult.fail(13009, "request already processing");
            } else {
                // failed — delete and retry
                log.info("Idempotent hit (failed), retrying: key={}", key);
                identityMapper.deleteIdempotencyLog(key);
                identityMapper.insertIdempotencyLog(key, request.getOperation(), deviceId, "processing");
            }
        }

        try {
            Device device = deviceMapper.getDeviceByDeviceId(deviceId);
            boolean created;

            if (device == null) {
                device = buildNewDevice(deviceId, payload);
                identityMapper.insertDevice(device);
                created = true;
                log.info("IAM register: created device {} with sipHa1", deviceId);
            } else {
                updateDevice(device, payload);
                created = false;
                log.info("IAM register: updated device {} with new sipHa1", deviceId);
            }

            // Mark idempotency as success
            identityMapper.updateIdempotencyStatus(key, "success");
            return DeviceIdentityResult.ok(deviceId, created);
        } catch (Exception e) {
            // Mark idempotency as failed to allow retry
            identityMapper.updateIdempotencyStatus(key, "failed");
            throw e;
        }
    }

    private Device buildNewDevice(String deviceId, IamSyncPayloadSpecific payload) {
        Device device = new Device();
        device.setDeviceId(deviceId);
        device.setName(payload.getDeviceName());
        device.setSipHa1(payload.getSipHa1());
        device.setStreamMode(payload.getStreamMode() != null ? payload.getStreamMode() : "TCP-PASSIVE");
        // transport (UDP/TCP) is NOT set here — determined at SIP REGISTER time from ViaHeader
        device.setCharset(payload.getCharset() != null ? payload.getCharset() : "GB2312");
        device.setMediaServerId(payload.getMediaServerId() != null ? payload.getMediaServerId() : "auto");
        device.setSsrcCheck(payload.getSsrcCheck() != null ? payload.getSsrcCheck() : false);
        device.setGeoCoordSys(payload.getGeoCoordSys() != null ? payload.getGeoCoordSys() : "WGS84");
        device.setAsMessageChannel(payload.getAsMessageChannel() != null ? payload.getAsMessageChannel() : false);
        device.setBroadcastPushAfterAck(payload.getBroadcastPushAfterAck() != null ? payload.getBroadcastPushAfterAck() : false);
        device.setHeartBeatInterval(payload.getHeartbeatInterval() != null ? payload.getHeartbeatInterval() : 60);
        device.setHeartBeatCount(payload.getHeartbeatCount() != null ? payload.getHeartbeatCount() : 3);
        device.setDisabled(false);
        device.setActivated(true);
        device.setPassword(null);
        device.setExpires(3600);
        device.setOnLine(false);
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        device.setCreateTime(now);
        device.setUpdateTime(now);
        device.setServerId("auto");
        if (payload.getSdpIp() != null) {
            device.setSdpIp(payload.getSdpIp());
        }
        return device;
    }

    private void updateDevice(Device device, IamSyncPayloadSpecific payload) {
        device.setSipHa1(payload.getSipHa1());
        if (payload.getDeviceName() != null) device.setName(payload.getDeviceName());
        if (payload.getCharset() != null) device.setCharset(payload.getCharset());
        if (payload.getMediaServerId() != null) device.setMediaServerId(payload.getMediaServerId());
        if (payload.getStreamMode() != null) device.setStreamMode(payload.getStreamMode());
        // transport (UDP/TCP) is NOT updated here — determined at SIP REGISTER time from ViaHeader
        if (payload.getSsrcCheck() != null) device.setSsrcCheck(payload.getSsrcCheck());
        if (payload.getGeoCoordSys() != null) device.setGeoCoordSys(payload.getGeoCoordSys());
        if (payload.getAsMessageChannel() != null) device.setAsMessageChannel(payload.getAsMessageChannel());
        if (payload.getBroadcastPushAfterAck() != null) device.setBroadcastPushAfterAck(payload.getBroadcastPushAfterAck());
        if (payload.getHeartbeatInterval() != null) device.setHeartBeatInterval(payload.getHeartbeatInterval());
        if (payload.getHeartbeatCount() != null) device.setHeartBeatCount(payload.getHeartbeatCount());
        if (payload.getSdpIp() != null) device.setSdpIp(payload.getSdpIp());
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        device.setUpdateTime(now);
        identityMapper.updateDevice(device);
    }

    public static class DeviceIdentityResult {
        public final int code;
        public final String msg;
        public final String deviceId;
        public final Boolean created;

        private DeviceIdentityResult(int code, String msg, String deviceId, Boolean created) {
            this.code = code;
            this.msg = msg;
            this.deviceId = deviceId;
            this.created = created;
        }

        public static DeviceIdentityResult ok(String deviceId, boolean created) {
            return new DeviceIdentityResult(0, "success", deviceId, created);
        }

        public static DeviceIdentityResult fail(int code, String msg) {
            return new DeviceIdentityResult(code, msg, null, null);
        }
    }
}
```

**Step 2: Verify compilation**

Run: `mvn compile -pl . -q`
Expected: BUILD SUCCESS.

**Step 3: Commit**

```bash
git add src/main/java/com/genersoft/iot/vmp/jxt/identity/service/DeviceIdentityService.java
git commit -m "feat(W05): Add DeviceIdentityService — IAM register with idempotency"
```

---

## Task 12: DeviceIdentityController

**Files:**
- Create: `src/main/java/com/genersoft/iot/vmp/jxt/identity/controller/DeviceIdentityController.java`

**Pattern reference:** `CameraChannelController.java` — uses `@RequestMapping(value = "/api/sy")` + `@ConditionalOnProperty(value = "sy.enable", havingValue = "true")`

**Step 1: Create DeviceIdentityController**

```java
package com.genersoft.iot.vmp.jxt.identity.controller;

import com.genersoft.iot.vmp.conf.SipConfig;
import com.genersoft.iot.vmp.jxt.identity.dto.IamSyncRequest;
import com.genersoft.iot.vmp.jxt.identity.service.DeviceIdentityService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.util.ObjectUtils;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.regex.Pattern;

@Slf4j
@RestController
@RequestMapping(value = "/api/sy")
@ConditionalOnProperty(value = "jxt.identity.controller.enabled", havingValue = "true", matchIfMissing = true)
public class DeviceIdentityController {

    private static final Pattern DEVICE_ID_PATTERN = Pattern.compile("^\\d{20}$");
    private static final Pattern HA1_PATTERN = Pattern.compile("^[0-9a-fA-F]{32}$");

    @Autowired
    private DeviceIdentityService identityService;

    @Autowired
    private SipConfig sipConfig;

    @PostMapping(value = "/device", consumes = "application/json", produces = "application/json")
    public ResponseEntity<Map<String, Object>> register(@RequestBody IamSyncRequest request) {
        log.info("IAM sync: operation={}, device={}, key={}", request.getOperation(),
                request.getTargetDeviceId(), request.getIdempotencyKey());

        // --- Input validation ---
        if (request.getSchemaVersion() != 1) {
            return fail(13001, "Unsupported schema_version: expected 1, got " + request.getSchemaVersion());
        }
        if (!"register".equals(request.getOperation())) {
            return fail(13002, "Unsupported operation: expected 'register', got '" + request.getOperation() + "'");
        }
        if (request.getTargetDeviceId() == null || !DEVICE_ID_PATTERN.matcher(request.getTargetDeviceId()).matches()) {
            return fail(13003, "Invalid target_deviceId: expected 20-digit number");
        }
        if (ObjectUtils.isEmpty(request.getIdempotencyKey())) {
            return fail(13006, "Missing idempotency_key");
        }
        if (request.getPayloadSpecific() == null) {
            return fail(13007, "Missing payload_specific");
        }
        if (ObjectUtils.isEmpty(request.getPayloadSpecific().getSipHa1())) {
            return fail(13007, "Missing payload_specific.sipHa1");
        }
        if (!HA1_PATTERN.matcher(request.getPayloadSpecific().getSipHa1()).matches()) {
            return fail(13004, "Invalid sipHa1 format: expected 32 hex chars (MD5 digest)");
        }
        if (ObjectUtils.isEmpty(request.getPayloadSpecific().getRealm())) {
            return fail(13008, "Missing payload_specific.realm");
        }
        if (!request.getPayloadSpecific().getRealm().equals(sipConfig.getDomain())) {
            return fail(13005, "Realm mismatch: expected '" + sipConfig.getDomain() +
                    "', got '" + request.getPayloadSpecific().getRealm() + "'");
        }

        // --- Process ---
        DeviceIdentityService.DeviceIdentityResult result = identityService.register(request);
        if (result.code != 0) {
            return fail(result.code, result.msg);
        }

        return ResponseEntity.ok(Map.of(
                "code", 0,
                "msg", "success",
                "data", Map.of(
                        "deviceId", result.deviceId,
                        "created", result.created
                )
        ));
    }

    private ResponseEntity<Map<String, Object>> fail(int code, String msg) {
        log.warn("IAM sync rejected: code={}, msg={}", code, msg);
        return ResponseEntity.ok(Map.of("code", code, "msg", msg));
    }
}
```

**Step 2: Verify compilation**

Run: `mvn compile -pl . -q`
Expected: BUILD SUCCESS.

**Step 3: Commit**

```bash
git add src/main/java/com/genersoft/iot/vmp/jxt/identity/controller/DeviceIdentityController.java
git commit -m "feat(W05): Add DeviceIdentityController — POST /api/sy/device register endpoint"
```

---

## Task 13: Configuration Properties

**Files:**
- Create: `src/main/java/com/genersoft/iot/vmp/jxt/identity/config/IdentityConfig.java`

**Step 1: Create configuration class**

```java
package com.genersoft.iot.vmp.jxt.identity.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "jxt.identity")
public class IdentityConfig {
    private boolean enabled = true;
    private ControllerConfig controller = new ControllerConfig();
    private StrategyConfig strategy = new StrategyConfig();
    private IdempotencyConfig idempotency = new IdempotencyConfig();

    @Data
    public static class ControllerConfig {
        private boolean enabled = true;
    }

    @Data
    public static class StrategyConfig {
        private boolean ha1Enabled = true;
        private boolean plaintextEnabled = true;
    }

    @Data
    public static class IdempotencyConfig {
        private int cleanupDays = 7;
    }
}
```

**Step 2: Verify compilation**

Run: `mvn compile -pl . -q`
Expected: BUILD SUCCESS.

**Step 3: Commit**

```bash
git add src/main/java/com/genersoft/iot/vmp/jxt/identity/config/IdentityConfig.java
git commit -m "feat: Add IdentityConfig — @ConfigurationProperties for jxt.identity.*"
```

---

## Task 14: Full Build Verification

**Files:**
- No new files

**Step 1: Run Maven clean compile**

Run: `mvn clean compile -pl .`
Expected: BUILD SUCCESS. No compilation errors.

**Step 2: Run Maven test (if applicable)**

Run: `mvn test -pl . -DskipTests=false`
Expected: All existing tests pass. New code is not yet tested by existing test suite.

**Step 3: Run full package**

Run: `mvn clean package -DskipTests`
Expected: BUILD SUCCESS. `target/wvp-pro-*.jar` created.

**Step 4: Commit (if any fixups were needed)**

```bash
git add -A
git commit -m "fix: Address compilation issues from integration"
```

---

## Task 15: Manual Integration Test — IAM Register Endpoint

**Prerequisites:**
- WVP running locally with `sy.enable=true` in `application-dev.yml`
- MySQL migrated with Task 1 script

**Step 1: Test valid register (device creation)**

```bash
curl -X POST http://localhost:18080/api/sy/device?appKey={appKey}&accessToken={accessToken}&timestamp={ts}&sign={sign} \
  -H "Content-Type: application/json" \
  -d '{
    "schema_version": 1,
    "idempotency_key": "test-reg-001",
    "trace_id": "00-test001-01",
    "tenant_id": 1,
    "target_deviceId": "34020000001320000001",
    "operation": "register",
    "occurred_at": "2026-05-16T10:00:00Z",
    "payload_specific": {
      "deviceName": "TestCamera",
      "sipHa1": "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4",
      "realm": "{sip.domain from config}",
      "charset": "GB2312",
      "streamMode": "TCP-PASSIVE",
      "heartbeatInterval": 60,
      "heartbeatCount": 3
    }
  }'
```

Expected: `{"code":0,"msg":"success","data":{"deviceId":"34020000001320000001","created":true}}`

**Step 2: Verify database**

```sql
SELECT device_id, sip_ha1, disabled, activated FROM wvp_device WHERE device_id = '34020000001320000001';
-- Expected: sip_ha1 = 'a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4', disabled = 0, activated = 1

SELECT * FROM wvp_idempotency_log WHERE idempotency_key = 'test-reg-001';
-- Expected: 1 row, operation='register', device_id='34020000001320000001', status='success'
```

**Step 3: Test idempotent re-send**

Send the same request again with the same `idempotency_key`.

Expected: `{"code":0,"msg":"success","data":{"deviceId":"34020000001320000001","created":false}}`

**Step 4: Test validation errors**

| Test | Expected code |
|------|--------------|
| `schema_version: 2` | 13001 |
| `operation: "update"` | 13002 |
| `target_deviceId: "abc"` | 13003 |
| `sipHa1: "xyz"` | 13004 |
| `realm: "wrong"` | 13005 |
| Missing `idempotency_key` | 13006 |

**Step 5: Test device update (existing device)**

Send a register with a different `idempotency_key` but same `target_deviceId` and a new `sipHa1`.

Expected: `{"code":0,"msg":"success","data":{"deviceId":"34020000001320000001","created":false}}`

Verify DB: `sip_ha1` updated to new value.

---

## Task 16: Manual Integration Test — SIP Register with HA1

**Prerequisites:**
- Task 15 completed: device `34020000001320000001` exists in DB with `sip_ha1` set
- Real ZX terminal or SIP test client configured with matching credentials

**Step 1: Terminal sends SIP REGISTER**

Monitor WVP logs:
```
grep "Ha1Strategy" logs/wvp.log
```

Expected: `Ha1Strategy: device 34020000001320000001 authenticated via HA1 digest`

**Step 2: Verify device online**

```sql
SELECT device_id, on_line, sip_ha1 FROM wvp_device WHERE device_id = '34020000001320000001';
-- Expected: on_line = 1
```

**Step 3: Test legacy device (no sip_ha1) still works**

Register a device that has `password` set but `sip_ha1 = null`.

Expected: `PlaintextStrategy: device ... authenticated via plaintext password`

**Step 4: Test no-auth device (no sip_ha1, no password, no global password)**

Set `sip.password` to empty in config, register a device with null password and null sip_ha1.

Expected: Strategy chain returns SKIP → global password empty → no-auth mode → SUCCESS.

---

## Summary: File Inventory

| Task | Action | File |
|------|--------|------|
| 1 | Create | `数据库/2.7.4/更新-mysql-2.7.4-jxt-device-identity.sql` |
| 2 | Modify | `src/.../gb28181/bean/Device.java` |
| 3 | Modify | `src/.../gb28181/dao/DeviceMapper.java` |
| 4 | Create | `src/.../jxt/identity/auth/DeviceAuthStrategy.java` |
| 4 | Create | `src/.../jxt/identity/auth/AuthResult.java` |
| 5 | Create | `src/.../jxt/identity/auth/Ha1Strategy.java` |
| 6 | Create | `src/.../jxt/identity/auth/PlaintextStrategy.java` |
| 7 | Create | `src/.../jxt/identity/auth/DeviceAuthStrategyChain.java` |
| 8 | Modify | `src/.../gb28181/transmit/event/request/impl/RegisterRequestProcessor.java` |
| 9 | Create | `src/.../jxt/identity/dto/IamSyncRequest.java` |
| 9 | Create | `src/.../jxt/identity/dto/IamSyncPayloadSpecific.java` |
| 10 | Create | `src/.../jxt/identity/mapper/DeviceIdentityMapper.java` |
| 11 | Create | `src/.../jxt/identity/service/DeviceIdentityService.java` |
| 12 | Create | `src/.../jxt/identity/controller/DeviceIdentityController.java` |
| 13 | Create | `src/.../jxt/identity/config/IdentityConfig.java` |
