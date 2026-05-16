# Iteration 2 WVP Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Implement WVP-side IAM credential sync endpoint for the unified terminal identity system.

**Architecture:** New `jxt.identity` package under `com.genersoft.iot.vmp.jxt.identity` with DeviceIdentityController (POST /api/sy/device register). Device table extended with `sip_ha1`, `disabled`, and `activated` columns. RegisterRequestProcessor strategy-chain refactor is out of scope for this narrowed W01 + W05 pass.

**Tech Stack:** Java 21, Spring Boot 3.4.4, MyBatis (annotation-based, no XML), JAIN-SIP, Hutool (SM3/SM4 in existing SignAuthenticationFilter), MySQL 8, Lombok

---

## Eng Review Decisions (2026-05-16)

| Decision | Choice | Plan impact |
|----------|--------|-------------|
| D1 Scope | **C: W01 + W05 only** | Do IAM register sync and DB changes only; defer W04 strategy-chain and RegisterRequestProcessor HA1 auth integration. |
| D3 Controller guard | **Double guard** | `DeviceIdentityController` must require both `sy.enable=true` and `jxt.identity.controller.enabled=true`. |
| D4 Transaction boundary | **Split transaction service** | Outer service handles idempotency; separate transaction service performs device insert/update. |
| D5 Heartbeat fields | **Use existing GB28181 fields** | Use `heart_beat_interval` / `heart_beat_count`; do not create `heartbeat_interval` / `heartbeat_count`. |
| D6 Schema scope | **W05-required schema only** | Keep `sip_ha1`, `disabled`, `activated`, `wvp_idempotency_log`; defer rotation/revocation/realm-transition schema. |
| D7 Response format | **Use `WVPResult`** | Controller returns `WVPResult<DeviceIdentityData>`, not ad-hoc `Map`. |
| D8 Update ownership | **Whitelist IAM fields** | W05 update must not touch GB28181 runtime fields like `ip`, `port`, `transport`, `on_line`, `host_address`. |
| D9 Device name | **Write `custom_name`** | IAM `deviceName` maps to `wvp_device.custom_name`, matching WVP custom-device semantics. |
| D10 JSON binding | **Use `@JsonProperty`** | Explicitly bind wire fields, especially `target_deviceId`. |
| D11 Tests | **W01 + W05 tests only** | Replace strategy-chain tests with IAM register DTO/controller/service/mapper tests. |
| D12 Idempotency cleanup | **Index `created_at`** | Add `idx_wvp_idempotency_log_created_at` for cleanup. |

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

CREATE PROCEDURE `wvp_idempotency_log_indexes`()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                   WHERE TABLE_SCHEMA = (SELECT DATABASE()) AND table_name = 'wvp_idempotency_log' AND index_name = 'idx_wvp_idempotency_log_created_at') THEN
        CREATE INDEX idx_wvp_idempotency_log_created_at ON wvp_idempotency_log(created_at);
    END IF;
END; //

CALL wvp_idempotency_log_indexes();
DROP PROCEDURE wvp_idempotency_log_indexes;

DELIMITER ;
```

**Step 2: Verify script syntax**

Run: `mysql -u root -p < 数据库/2.7.4/更新-mysql-2.7.4-jxt-device-identity.sql`
Expected: All procedures execute, tables/columns created. Re-running is idempotent (IF NOT EXISTS guards).

**Step 3: Commit**

```bash
git add 数据库/2.7.4/更新-mysql-2.7.4-jxt-device-identity.sql
git commit -m "feat(W01): SQL migration — Device identity columns + idempotency log"
```

---

## Task 2: Device.java Entity Fields

**Files:**
- Modify: `src/main/java/com/genersoft/iot/vmp/gb28181/bean/Device.java:193-214` (after `geoCoordSys`, before `password`)

**Step 1: Add new fields to Device.java**


```java
	@Schema(description = "HA1摘要 = MD5(deviceId:realm:password)")
	private String sipHa1;

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
"disabled," +
"activated," +
```

**Step 2: Add columns to `add` (line 51-115)**

In the column list (after `"on_line"+` at line 81), add:

```java
",sip_ha1" +
",disabled" +
",activated" +
```

In the values list (after `"#{onLine}" +` at line 112), add:

```java
",#{sipHa1}" +
",#{disabled}" +
",#{activated}" +
```

**Step 3: Add columns to `update` (line 117-141)**

Add after `", expires=#{expires}" +` (line 137), before `", server_id=#{serverId}" +` (line 138):

```java
", sip_ha1=#{sipHa1}" +
", disabled=#{disabled}" +
", activated=#{activated}" +
```

**Step 4: Add columns to `getDevices` (line 143-180)**

Add to the SELECT list after `"broadcast_push_after_ack,"+` (line ~170):

```java
"sip_ha1,"+
"disabled,"+
"activated,"+
```

**Step 5: Add columns to `updateCustom` (line 281-289)**

Add after `", geo_coord_sys=#{geoCoordSys}, media_server_id=#{mediaServerId}" +` (line 286):

```java
", sip_ha1=#{sipHa1}" +
", disabled=#{disabled}" +
", activated=#{activated}" +
```

**Step 6: Add columns to `addCustomDevice` (line 291-324)**

In the column list (after `"media_server_id"+` at line 306), add:

```java
",sip_ha1" +
",disabled" +
",activated" +
```

In the values list (after `"#{mediaServerId}" +` at line 322), add:

```java
",#{sipHa1}" +
",#{disabled}" +
",#{activated}" +
```

**Step 7: Add columns to `getOnlineDevices` (line 185-215)**

Add to the SELECT list after `"broadcast_push_after_ack,"+`:

```java
"sip_ha1,"+
"disabled,"+
"activated,"+
```

**Step 8: Add columns to `getOnlineDevicesByServerId` (line 217-248)**

Same pattern as Step 7.

**Step 9: Add columns to `getDeviceList` (line 332-377)**

Same pattern — add the 3 columns to the SELECT list.

**Step 10: Scan ALL remaining methods**

The methods listed above (Steps 1-9 + Step 11 for batchUpdate) are the confirmed ones. However, **any future additions to DeviceMapper.java that reference `wvp_device` must also include the 3 new columns (`sip_ha1`, `disabled`, `activated`)**. Rotation columns (`sip_ha1_previous`, `previous_valid_until`) are deferred to iteration 3 and must not be added in this narrowed W01 + W05 pass. Before closing Task 3, search for all `@Select`/`@Insert`/`@Update` annotations touching `wvp_device` and verify none were missed:

```bash
grep -n "wvp_device" src/main/java/com/genersoft/iot/vmp/gb28181/dao/DeviceMapper.java
```

Pay special attention to `batchUpdate()` (around line 414) — it updates all fields in a foreach loop.

**Step 11 (Review D1): Add columns to `batchUpdate()`**

In the SET clause of `batchUpdate()` (around line 414), add the 3 new columns:

```java
", sip_ha1=#{item.sipHa1}" +
", disabled=#{item.disabled}" +
", activated=#{item.activated}" +
```

**Step 12: Verify compilation**

Run: `mvn compile -pl . -q`
Expected: BUILD SUCCESS. All SELECT/INSERT/UPDATE annotations compile. At runtime, existing rows will have `null` for `sip_ha1` columns and `false`/`true` defaults for `disabled`/`activated`.

**Step 13: Commit**

```bash
git add src/main/java/com/genersoft/iot/vmp/gb28181/dao/DeviceMapper.java
git commit -m "feat(W01): Add sip_ha1 columns to all DeviceMapper annotation SQL"
```

---

## Deferred: W04 REGISTER HA1 Authentication

The original Task 4 through Task 8 content has been moved out of this narrowed W01 + W05 implementation plan.

Use this separate plan instead:

- `docs/plans/2026-05-16-iteration2-plus-wvp-implementation.md`

Moved scope:

- `AuthResult`
- `AuthCredentials`
- `DeviceAuthStrategy`
- `Ha1Strategy`
- `PlaintextStrategy`
- `DeviceAuthStrategyChain`
- `RegisterRequestProcessor` authentication refactor
- SIP REGISTER HA1 tests and manual E2E validation

Do **not** modify `RegisterRequestProcessor` in this W01 + W05 pass. IAM can write `sip_ha1`, but SIP REGISTER HA1 authentication is intentionally deferred until the plus plan is executed.

---

## Task 4: IAM Sync Request DTOs

**Files:**
- Create: `src/main/java/com/genersoft/iot/vmp/jxt/identity/dto/IamSyncRequest.java`
- Create: `src/main/java/com/genersoft/iot/vmp/jxt/identity/dto/IamSyncPayloadSpecific.java`

**Step 1: Create IamSyncPayloadSpecific**

**Review corrections:** Use explicit `@JsonProperty` for all IAM wire fields that are not identical to Java field names. `target_deviceId` is the exact IAM contract spelling; do not rename it to `target_device_id`.

```java
package com.genersoft.iot.vmp.jxt.identity.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
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
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class IamSyncRequest {
    @JsonProperty("schema_version")
    private int schemaVersion;

    @JsonProperty("idempotency_key")
    private String idempotencyKey;

    @JsonProperty("trace_id")
    private String traceId;

    @JsonProperty("tenant_id")
    private int tenantId;

    @JsonProperty("target_deviceId")
    private String targetDeviceId;

    private String operation;

    @JsonProperty("occurred_at")
    private String occurredAt;

    @JsonProperty("payload_specific")
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

## Task 5: DeviceIdentityMapper (MyBatis)

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
            "device_id, custom_name, sip_ha1, charset, media_server_id, " +
            "ssrc_check, geo_coord_sys, as_message_channel, broadcast_push_after_ack, " +
            "heart_beat_interval, heart_beat_count, disabled, activated, password, expires, " +
            "create_time, update_time, on_line, stream_mode, sdp_ip, server_id" +
            ") VALUES (" +
            "#{deviceId}, #{name}, #{sipHa1}, #{charset}, #{mediaServerId}, " +
            "#{ssrcCheck}, #{geoCoordSys}, #{asMessageChannel}, #{broadcastPushAfterAck}, " +
            "#{heartBeatInterval}, #{heartBeatCount}, #{disabled}, #{activated}, #{password}, #{expires}, " +
            "#{createTime}, #{updateTime}, #{onLine}, #{streamMode}, #{sdpIp}, #{serverId}" +
            ")")
    int insertDevice(Device device);

    @Update({"<script>",
            "UPDATE wvp_device SET update_time=#{updateTime}, sip_ha1=#{sipHa1}",
            "<if test='name != null'>, custom_name=#{name}</if>",
            "<if test='charset != null'>, charset=#{charset}</if>",
            "<if test='mediaServerId != null'>, media_server_id=#{mediaServerId}</if>",
            "<if test='ssrcCheck != null'>, ssrc_check=#{ssrcCheck}</if>",
            "<if test='geoCoordSys != null'>, geo_coord_sys=#{geoCoordSys}</if>",
            "<if test='asMessageChannel != null'>, as_message_channel=#{asMessageChannel}</if>",
            "<if test='broadcastPushAfterAck != null'>, broadcast_push_after_ack=#{broadcastPushAfterAck}</if>",
            "<if test='heartBeatInterval != null'>, heart_beat_interval=#{heartBeatInterval}</if>",
            "<if test='heartBeatCount != null'>, heart_beat_count=#{heartBeatCount}</if>",
            "<if test='disabled != null'>, disabled=#{disabled}</if>",
            "<if test='activated != null'>, activated=#{activated}</if>",
            "<if test='streamMode != null'>, stream_mode=#{streamMode}</if>",
            "<if test='sdpIp != null'>, sdp_ip=#{sdpIp}</if>",
            " WHERE device_id=#{deviceId}",
            "</script>"})
    int updateDevice(Device device);

    // --- Idempotency log (D4 Option B: no processing state) ---

    @Insert("INSERT INTO wvp_idempotency_log (idempotency_key, operation, device_id, status) " +
            "VALUES (#{idempotencyKey}, #{operation}, #{deviceId}, 'success')")
    int tryInsertIdempotencyLog(@Param("idempotencyKey") String key,
                                @Param("operation") String operation,
                                @Param("deviceId") String deviceId);

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

## Task 6: DeviceIdentityService

**Files:**
- Create: `src/main/java/com/genersoft/iot/vmp/jxt/identity/service/DeviceIdentityService.java`
- Create: `src/main/java/com/genersoft/iot/vmp/jxt/identity/service/DeviceIdentityTxService.java`

**Step 1: Create DeviceIdentityService (Review D4, D8, D9, D10, D11)**

**Review corrections:**
- Split transaction boundary: `DeviceIdentityService` handles idempotency; `DeviceIdentityTxService` owns the transactional insert/update.
- W05 update must be a whitelist update of IAM-owned fields only.
- IAM `deviceName` writes `custom_name` through `Device.name` in the dedicated mapper, matching WVP custom-device semantics.
- Do not touch GB28181 runtime fields (`ip`, `port`, `host_address`, `local_ip`, `transport`, `on_line`, `expires`) in update paths.

```java
package com.genersoft.iot.vmp.jxt.identity.service;

import com.genersoft.iot.vmp.jxt.identity.config.IdentityConfig;
import com.genersoft.iot.vmp.jxt.identity.dto.IamSyncRequest;
import com.genersoft.iot.vmp.jxt.identity.mapper.DeviceIdentityMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class DeviceIdentityService {

    @Autowired
    private DeviceIdentityMapper identityMapper;

    @Autowired
    private DeviceIdentityTxService txService;

    @Autowired
    private IdentityConfig identityConfig;

    public DeviceIdentityResult register(IamSyncRequest request) {
        String key = request.getIdempotencyKey();
        String deviceId = request.getTargetDeviceId();

        // Idempotency: INSERT → DuplicateKeyException = already processed (D4 Option B)
        // Runs outside @Transactional to persist regardless of business tx outcome (D8)
        try {
            identityMapper.tryInsertIdempotencyLog(key, request.getOperation(), deviceId);
        } catch (DuplicateKeyException e) {
            log.info("Idempotent hit: key={}, device={}", key, deviceId);
            return DeviceIdentityResult.ok(deviceId, false);
        }

        try {
            return txService.doRegister(request);
        } catch (Exception e) {
            // Allow IAM retry by removing the idempotency key (Review: widened from DataAccessException)
            identityMapper.deleteIdempotencyLog(key);
            log.error("Register failed for device {}, key={}: {}", deviceId, key, e.getMessage());
            throw e;
        }
    }

    @Scheduled(cron = "0 0 3 * * ?")
    public void cleanupIdempotencyLog() {
        int days = identityConfig.getIdempotency().getCleanupDays();
        int deleted = identityMapper.cleanOldEntries(days);
        if (deleted > 0) {
            log.info("Cleaned {} idempotency log entries older than {} days", deleted, days);
        }
    }

    public record DeviceIdentityResult(int code, String msg, String deviceId, Boolean created) {
        public static DeviceIdentityResult ok(String deviceId, boolean created) {
            return new DeviceIdentityResult(0, "success", deviceId, created);
        }

        public static DeviceIdentityResult fail(int code, String msg) {
            return new DeviceIdentityResult(code, msg, null, null);
        }
    }
}
```

**Step 1b: Create DeviceIdentityTxService**

```java
package com.genersoft.iot.vmp.jxt.identity.service;

import com.genersoft.iot.vmp.gb28181.bean.Device;
import com.genersoft.iot.vmp.gb28181.dao.DeviceMapper;
import com.genersoft.iot.vmp.jxt.identity.dto.IamSyncPayloadSpecific;
import com.genersoft.iot.vmp.jxt.identity.dto.IamSyncRequest;
import com.genersoft.iot.vmp.jxt.identity.mapper.DeviceIdentityMapper;
import com.genersoft.iot.vmp.storager.IRedisCatchStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Slf4j
@Service
public class DeviceIdentityTxService {
    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Autowired
    private DeviceIdentityMapper identityMapper;

    @Autowired
    private DeviceMapper deviceMapper;

    @Autowired
    private IRedisCatchStorage redisCatchStorage;

    @Transactional
    public DeviceIdentityService.DeviceIdentityResult doRegister(IamSyncRequest request) {
        String deviceId = request.getTargetDeviceId();
        IamSyncPayloadSpecific payload = request.getPayloadSpecific();
        Device device = deviceMapper.getDeviceByDeviceId(deviceId);
        boolean created;
        if (device == null) {
            device = buildNewDevice(deviceId, payload);
            identityMapper.insertDevice(device);
            created = true;
        } else {
            applyIamFields(device, payload);
            identityMapper.updateDevice(device);
            created = false;
        }
        // Sync Redis cache so W04 SIP REGISTER reads fresh HA1 data
        redisCatchStorage.updateDevice(device);
        return DeviceIdentityService.DeviceIdentityResult.ok(deviceId, created);
    }

    private Device buildNewDevice(String deviceId, IamSyncPayloadSpecific payload) {
        Device device = new Device();
        device.setDeviceId(deviceId);
        device.setName(payload.getDeviceName());
        device.setSipHa1(payload.getSipHa1());
        device.setStreamMode(payload.getStreamMode() != null ? payload.getStreamMode() : "TCP-PASSIVE");
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
        String now = LocalDateTime.now().format(DTF);
        device.setCreateTime(now);
        device.setUpdateTime(now);
        device.setServerId("auto");
        if (payload.getSdpIp() != null) {
            device.setSdpIp(payload.getSdpIp());
        }
        return device;
    }

    private void applyIamFields(Device device, IamSyncPayloadSpecific payload) {
        device.setSipHa1(payload.getSipHa1());
        if (payload.getDeviceName() != null) device.setName(payload.getDeviceName());
        if (payload.getCharset() != null) device.setCharset(payload.getCharset());
        if (payload.getMediaServerId() != null) device.setMediaServerId(payload.getMediaServerId());
        if (payload.getStreamMode() != null) device.setStreamMode(payload.getStreamMode());
        if (payload.getSsrcCheck() != null) device.setSsrcCheck(payload.getSsrcCheck());
        if (payload.getGeoCoordSys() != null) device.setGeoCoordSys(payload.getGeoCoordSys());
        if (payload.getAsMessageChannel() != null) device.setAsMessageChannel(payload.getAsMessageChannel());
        if (payload.getBroadcastPushAfterAck() != null) device.setBroadcastPushAfterAck(payload.getBroadcastPushAfterAck());
        if (payload.getHeartbeatInterval() != null) device.setHeartBeatInterval(payload.getHeartbeatInterval());
        if (payload.getHeartbeatCount() != null) device.setHeartBeatCount(payload.getHeartbeatCount());
        if (payload.getSdpIp() != null) device.setSdpIp(payload.getSdpIp());
        device.setUpdateTime(LocalDateTime.now().format(DTF));
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

## Task 7: DeviceIdentityController

**Files:**
- Create: `src/main/java/com/genersoft/iot/vmp/jxt/identity/controller/DeviceIdentityController.java`

**Pattern reference:** `CameraChannelController.java` — uses `@RequestMapping(value = "/api/sy")` + `@ConditionalOnProperty(value = "sy.enable", havingValue = "true")`

**Step 1: Create DeviceIdentityController**

```java
package com.genersoft.iot.vmp.jxt.identity.controller;

import com.genersoft.iot.vmp.conf.SipConfig;
import com.genersoft.iot.vmp.jxt.identity.dto.IamSyncRequest;
import com.genersoft.iot.vmp.jxt.identity.service.DeviceIdentityService;
import com.genersoft.iot.vmp.vmanager.bean.WVPResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.util.ObjectUtils;
import org.springframework.web.bind.annotation.*;

import java.util.regex.Pattern;

@Slf4j
@RestController
@RequestMapping(value = "/api/sy")
@ConditionalOnProperty(value = "jxt.identity.controller.enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(value = "sy.enable", havingValue = "true")
public class DeviceIdentityController {

    private static final Pattern DEVICE_ID_PATTERN = Pattern.compile("^\\d{20}$");
    private static final Pattern HA1_PATTERN = Pattern.compile("^[0-9a-fA-F]{32}$");

    @Autowired
    private DeviceIdentityService identityService;

    @Autowired
    private SipConfig sipConfig;

    @PostMapping(value = "/device", consumes = "application/json", produces = "application/json")
    public WVPResult<DeviceIdentityData> register(@RequestBody IamSyncRequest request) {
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
            return fail(13009, "Missing payload_specific");
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
        if (result.code() != 0) {
            return fail(result.code(), result.msg());
        }

        return WVPResult.success(new DeviceIdentityData(result.deviceId(), result.created()));
    }

    private WVPResult<DeviceIdentityData> fail(int code, String msg) {
        log.warn("IAM sync rejected: code={}, msg={}", code, msg);
        return WVPResult.fail(code, msg);
    }

    public record DeviceIdentityData(String deviceId, Boolean created) {}
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

## Task 8: Configuration Properties

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

## Task 9: Full Build Verification

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

## Task 10: Manual Integration Test — IAM Register Endpoint

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
| Missing `payload_specific` (null) | 13009 |

**Step 5: Test device update (existing device)**

Send a register with a different `idempotency_key` but same `target_deviceId` and a new `sipHa1`.

Expected: `{"code":0,"msg":"success","data":{"deviceId":"34020000001320000001","created":false}}`

Verify DB: `sip_ha1` updated to new value.

---

## Task 11 (Review D11): W01 + W05 Tests

**Files:**
- Create: `src/test/java/com/genersoft/iot/vmp/jxt/identity/dto/IamSyncRequestJsonTest.java`
- Create: `src/test/java/com/genersoft/iot/vmp/jxt/identity/controller/DeviceIdentityControllerTest.java`
- Create: `src/test/java/com/genersoft/iot/vmp/jxt/identity/service/DeviceIdentityServiceTest.java`
- Create: `src/test/java/com/genersoft/iot/vmp/jxt/identity/service/DeviceIdentityTxServiceTest.java`
- Create: `src/test/resources/application-test.yml`

**Prerequisites:**
- Create `src/test/resources/application-test.yml` (minimal config for test context)
- Ensure `maven-surefire-plugin` skip is overridden (`-DskipTests=false`)

**Step 1: DTO JSON binding tests**

`IamSyncRequestJsonTest` must deserialize the exact IAM JSON wire payload and assert:
- `schema_version` → `schemaVersion`
- `idempotency_key` → `idempotencyKey`
- `target_deviceId` → `targetDeviceId`
- `occurred_at` → `occurredAt`
- `payload_specific` → `payloadSpecific`

**Step 2: Controller validation and response tests**

`DeviceIdentityControllerTest` must assert:
- valid register returns `WVPResult` with `code=0` and `data.deviceId/created`
- invalid `schema_version` returns `13001`
- invalid operation returns `13002`
- invalid `target_deviceId` returns `13003`
- invalid `sipHa1` returns `13004`
- realm mismatch returns `13005`
- missing `idempotency_key` returns `13006`
- missing `payload_specific.sipHa1` returns `13007`
- missing `payload_specific.realm` returns `13008`
- missing `payload_specific` (entire object) returns `13009`

**Step 3: Service idempotency + cleanup tests**

`DeviceIdentityServiceTest` must assert:
- first request inserts idempotency key and delegates to `DeviceIdentityTxService`
- duplicate key returns `created=false` without calling transactional service
- `DataAccessException` from transactional service deletes the idempotency key and rethrows
- `cleanupIdempotencyLog()` calls `cleanOldEntries(identityConfig.idempotency.cleanupDays)`

**Step 4: Transactional write-path tests**

`DeviceIdentityTxServiceTest` must assert:
- new device writes `custom_name`, `sip_ha1`, `heart_beat_interval`, `heart_beat_count`, defaults, and `on_line=false`
- existing device update only changes IAM whitelist fields
- update does not change `ip`, `port`, `host_address`, `local_ip`, `transport`, `on_line`, `expires`, or `server_id`
- after insert or update, `redisCatchStorage.updateDevice()` is called with the device (verify using mock)

**Step 5: Run tests**

```bash
mvn test -pl . -DskipTests=false
```

Expected: BUILD SUCCESS. All tests pass.

**Step 6: Commit**

```bash
git add src/test/
git commit -m "test(W05): Add IAM register DTO, controller, idempotency, and write-path tests"
```

---

## Deferred: SIP REGISTER HA1 Manual E2E

Manual SIP REGISTER HA1 validation is not part of this W01 + W05 plan because `RegisterRequestProcessor` remains unchanged here.

Run it after completing:

- `docs/plans/2026-05-16-iteration2-plus-wvp-implementation.md`

---

## Summary: File Inventory

| Task | Action | File |
|------|--------|------|
| 1 | Create | `数据库/2.7.4/更新-mysql-2.7.4-jxt-device-identity.sql` |
| 2 | Modify | `src/.../gb28181/bean/Device.java` |
| 3 | Modify | `src/.../gb28181/dao/DeviceMapper.java` |
| Deferred | Moved | W04 auth strategy chain + `RegisterRequestProcessor` HA1 integration → `2026-05-16-iteration2-plus-wvp-implementation.md` |
| 4 | Create | `src/.../jxt/identity/dto/IamSyncRequest.java` |
| 4 | Create | `src/.../jxt/identity/dto/IamSyncPayloadSpecific.java` |
| 5 | Create | `src/.../jxt/identity/mapper/DeviceIdentityMapper.java` |
| 6 | Create | `src/.../jxt/identity/service/DeviceIdentityService.java` |
| 6 | Create | `src/.../jxt/identity/service/DeviceIdentityTxService.java` |
| 7 | Create | `src/.../jxt/identity/controller/DeviceIdentityController.java` |
| 8 | Optional | `src/.../jxt/identity/config/IdentityConfig.java` |
| 11 | Create | `src/test/.../jxt/identity/dto/IamSyncRequestJsonTest.java` |
| 11 | Create | `src/test/.../jxt/identity/controller/DeviceIdentityControllerTest.java` |
| 11 | Create | `src/test/.../jxt/identity/service/DeviceIdentityServiceTest.java` |
| 11 | Create | `src/test/.../jxt/identity/service/DeviceIdentityTxServiceTest.java` |

---
## 后续迭代

| 编号 | 项 | 说明 | 期望迭代 |
|------|-----|------|----------|
| TODO-1 | Micrometer Metrics | 在 Authenticator 和策略链中添加认证成功/失败/策略命中率埋点，暴露 /actuator/metrics 端点 | Iteration 3 |
| TODO-2 | 零测试文化治理 | Surefire 解除全局 `<skipTests>true`，建立测试基类和规范 | Iteration 3 |
| TODO-3 | disabled 与在线流联动 | IAM 设置 `disabled=true` 时，BYE 断开当前 INVITE 会话 + 拒绝后续 INVITE | Iteration 3 |
| TODO-4 | 双 HA1 轮换逻辑 | 迭代 3 再新增 `sip_ha1_previous` + `previous_valid_until` schema，并实现 Ha1PreviousStrategy 策略化轮换（双发窗口 + 过期清理），**不允许直接覆盖旧 HA1** | Iteration 3 |

---
## 日志风格规范 (Review D13)

全部新代码统一使用 SLF4J 参数化风格：

```java
// ✅ 正确
log.info("device {} auth failed: {}", deviceId, reason);
log.warn("DeviceIdentityController: device {} sync rejected", deviceId);
log.error("Register failed for device {}: {}", deviceId, e.getMessage());

// ❌ 避免 — 字符串拼接
log.info("device " + deviceId + " auth failed: " + reason);
```
