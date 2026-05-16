# Iteration 2+ WVP Implementation Plan

> Date: 2026-05-16  
> Source: content deferred from `2026-05-16-iteration2-wvp-implementation.md` after eng review scope reduction.  
> Depends on: narrowed W01 + W05 plan completed first.  
> Scope: W04 REGISTER HA1 authentication chain, SIP register integration, HA1 E2E tests, and iteration-3-prep items that were excluded from the narrowed W01 + W05 pass.

---

## Eng Review Decisions (2026-05-16)

| Decision | Choice | Plan impact |
|----------|--------|-------------|
| D1 Scope | Keep strategy chain | W04 remains strategy-chain based because disabled/activated/rotation are known follow-up strategies. |
| D2 Strategy input | Use `SIPRequest` | Do not introduce `AuthCredentials` in this pass; reuse existing request-based digest helper methods. |
| D3 Kill switch | Runtime legacy fallback | `jxt.identity.enabled=false` must route REGISTER auth through the legacy password/global-password path. |
| D4 Bean lifecycle | Chain Bean always exists | Do not condition `DeviceAuthStrategyChain` on `jxt.identity.enabled`; the flag controls calls, not Bean creation. |

---

## 0. Scope and Non-Goals

### In scope

- Implement W04 strategy-chain authentication for SIP REGISTER.
- Add `Ha1Strategy` and `PlaintextStrategy`.
- Refactor `RegisterRequestProcessor` to delegate authentication to the strategy chain.
- Add SIP REGISTER HA1 manual and automated regression coverage.
- Add future-ready design notes for disabled/activated, HA1 rotation, realm fallback, revocation, and nonce replay protection.

### Not in scope

- W05 IAM register endpoint implementation. This belongs to `2026-05-16-iteration2-wvp-implementation.md`.
- Basic `sip_ha1`, `disabled`, `activated`, and `wvp_idempotency_log` schema. These belong to the narrowed W01 + W05 plan.
- Full iteration 3 credential rotation, revocation worker, or NonceStore production implementation unless explicitly promoted into this plan later.

### Implementation warnings

- Existing `DigestServerAuthenticationHelper` exposes request-based verification:
  - `doAuthenticateHashedPassword(Request request, String hashedPassword)`
  - `doAuthenticatePlainTextPassword(Request request, String pass)`
- Use `SIPRequest` in the strategy interface for this W04 pass. Do not introduce `AuthCredentials` unless a separate helper adapter is implemented and fully tested.
- Preserve the current re-registration fast path in `RegisterRequestProcessor`; this plan only replaces initial authentication decision logic.

---

## 1. Prerequisites

Before starting this plan, verify:

- W01 + W05 has been implemented and deployed.
- `wvp_device.sip_ha1` exists.
- IAM can call `POST /api/sy/device` successfully.
- WVP can store `sip_ha1` for a device.
- `DeviceMapper.getDeviceByDeviceId()` returns `sip_ha1`, `disabled`, and `activated`.
- W05 sync updates Redis cache with `redisCatchStorage.updateDevice(device)` after device insert/update, because `RegisterRequestProcessor` reads via `DeviceServiceImpl#getDeviceByDeviceId()` and may hit Redis first.
- Existing legacy GB28181 devices still register using password/global-password flow.

---

## 2. Boundary with Narrowed Iteration 2 Plan

| Area | Main W01 + W05 plan | This 2+ plan |
|------|----------------------|--------------|
| SQL baseline | Adds `sip_ha1`, `disabled`, `activated`, `wvp_idempotency_log` | Assumes baseline exists |
| IAM register endpoint | Implements `/api/sy/device` | Does not change endpoint |
| SIP REGISTER auth | Explicitly out of scope | Main scope |
| Strategy chain | Deferred | Implemented here |
| `RegisterRequestProcessor` | Do not modify | Refactor auth block here |
| HA1 rotation schema | Deferred | Optional prep only |
| Revocation/realm transition schema | Deferred | Optional prep only |
| Test focus | DTO/controller/service/mapper for IAM register | SIP auth strategies and REGISTER regression |

---

## 3. Target Data Flow

```text
IAM register sync
  -> WVP /api/sy/device
  -> wvp_device.sip_ha1 populated
  -> terminal sends SIP REGISTER
  -> RegisterRequestProcessor loads Device
  -> DeviceAuthStrategyChain
       ├── Ha1Strategy        : sip_ha1 digest auth
       └── PlaintextStrategy  : legacy password fallback
  -> 200 OK / 401 / 403
```

Key behavior:

| Scenario | Expected result |
|----------|-----------------|
| IAM-pushed device with `sip_ha1`, no password | `Ha1Strategy` authenticates successfully |
| Legacy device with password, no `sip_ha1` | `PlaintextStrategy` authenticates successfully |
| Device has neither `sip_ha1` nor password, global password set | Global password fallback authenticates |
| Device has neither `sip_ha1` nor password, no global password | No-auth mode keeps current WVP behavior |
| Wrong digest response | 403 wrong password |
| Missing Authorization when auth required | 401 challenge |

---

## 3.1 What Already Exists

| Existing code/flow | Reuse decision |
|--------------------|----------------|
| `DigestServerAuthenticationHelper#doAuthenticateHashedPassword(Request, String)` | Reuse directly in `Ha1Strategy`; do not duplicate digest math. |
| `DigestServerAuthenticationHelper#doAuthenticatePlainTextPassword(Request, String)` | Reuse directly in `PlaintextStrategy` and legacy fallback helper. |
| `RegisterRequestProcessor` re-registration fast path | Preserve unchanged. |
| `RegisterRequestProcessor` legacy password/global-password auth block | Extract into helper and use as `jxt.identity.enabled=false` kill-switch fallback. |
| `DeviceServiceImpl#getDeviceByDeviceId()` | Continue using it, but require W05 Redis cache refresh after IAM sync. |
| `IRedisCatchStorage#updateDevice(Device)` | W05 must call this after insert/update so SIP REGISTER sees fresh `sip_ha1`. |

---

## 3.2 NOT in Scope

| Item | Rationale |
|------|-----------|
| Rewriting SIP digest algorithm | Existing helper already implements qop/cnonce/nc handling. |
| `AuthCredentials` DTO | Deferred until field-level digest helper, NonceStore, or audit needs it. |
| Nonce replay protection | Separate NonceStore design; not required for HA1 enablement. |
| Disabled/activated enforcement | Product semantics need separate decision for online streams and INVITE handling. |
| HA1 rotation/revocation/realm fallback production logic | Iteration 3 work; optional schema prep only. |

---

## 4. Task 1: Auth Strategy Interface

**Files:**

- Create: `src/main/java/com/genersoft/iot/vmp/jxt/identity/auth/AuthResult.java`
- Create: `src/main/java/com/genersoft/iot/vmp/jxt/identity/auth/DeviceAuthStrategy.java`

### AuthResult

```java
package com.genersoft.iot.vmp.jxt.identity.auth;

public enum AuthResult {
    SUCCESS,
    FAIL,
    SKIP
}
```

### DeviceAuthStrategy

```java
package com.genersoft.iot.vmp.jxt.identity.auth;

import com.genersoft.iot.vmp.gb28181.bean.Device;
import gov.nist.javax.sip.message.SIPRequest;

public interface DeviceAuthStrategy {
    int priority();
    AuthResult authenticate(Device device, SIPRequest request);
}
```

### Verification

Run:

```bash
mvn compile -pl . -q
```

---

## 5. Task 2: Ha1Strategy

**Files:**

- Create: `src/main/java/com/genersoft/iot/vmp/jxt/identity/auth/Ha1Strategy.java`

**Reference:** `DigestServerAuthenticationHelper#doAuthenticateHashedPassword(...)`.

```java
package com.genersoft.iot.vmp.jxt.identity.auth;

import com.genersoft.iot.vmp.gb28181.auth.DigestServerAuthenticationHelper;
import com.genersoft.iot.vmp.gb28181.bean.Device;
import gov.nist.javax.sip.message.SIPRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.ObjectUtils;

@Slf4j
@Component
@ConditionalOnProperty(value = "jxt.identity.strategy.ha1-enabled", havingValue = "true", matchIfMissing = true)
public class Ha1Strategy implements DeviceAuthStrategy {

    @Autowired
    private DigestServerAuthenticationHelper digestHelper;

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
            boolean ok = digestHelper.doAuthenticateHashedPassword(request, device.getSipHa1());
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

---

## 6. Task 3: PlaintextStrategy

**Files:**

- Create: `src/main/java/com/genersoft/iot/vmp/jxt/identity/auth/PlaintextStrategy.java`

**Reference:** `DigestServerAuthenticationHelper#doAuthenticatePlainTextPassword(...)`.

```java
package com.genersoft.iot.vmp.jxt.identity.auth;

import com.genersoft.iot.vmp.gb28181.auth.DigestServerAuthenticationHelper;
import com.genersoft.iot.vmp.gb28181.bean.Device;
import gov.nist.javax.sip.message.SIPRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.ObjectUtils;

@Slf4j
@Component
@ConditionalOnProperty(value = "jxt.identity.strategy.plaintext-enabled", havingValue = "true", matchIfMissing = true)
public class PlaintextStrategy implements DeviceAuthStrategy {

    @Autowired
    private DigestServerAuthenticationHelper digestHelper;

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
            boolean ok = digestHelper.doAuthenticatePlainTextPassword(request, password);
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

---

## 7. Task 4: DeviceAuthStrategyChain

**Files:**

- Create: `src/main/java/com/genersoft/iot/vmp/jxt/identity/auth/DeviceAuthStrategyChain.java`

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

---

## 8. Task 5: RegisterRequestProcessor Refactor

**Files:**

- Modify: `src/main/java/com/genersoft/iot/vmp/gb28181/transmit/event/request/impl/RegisterRequestProcessor.java`

### Rules

- Preserve the existing re-registration fast path using `SipTransactionInfo` and matching `CallId`.
- Only replace the password resolution and authentication block.
- Keep existing unknown-device behavior when global password is disabled.
- Do not change online/offline state transitions in this task.
- `jxt.identity.enabled=false` must be a real runtime kill switch: WVP must use the legacy password/global-password authentication path without requiring `DeviceAuthStrategyChain`.

### Add dependencies

```java
@Autowired
private DeviceAuthStrategyChain strategyChain;

@Autowired
private DigestServerAuthenticationHelper digestHelper;

@Value("${jxt.identity.enabled:true}")
private boolean identityEnabled;
```

Imports:

```java
import com.genersoft.iot.vmp.jxt.identity.auth.AuthResult;
import com.genersoft.iot.vmp.jxt.identity.auth.DeviceAuthStrategyChain;
import org.springframework.beans.factory.annotation.Value;
```

### Authentication flow

```text
REGISTER request
  -> load Device
  -> preserve re-registration fast path
  -> if jxt.identity.enabled=false:
       use legacy password/global-password auth path
  -> determine needsAuth
       needsAuth = device.sipHa1 exists OR device.password exists OR global password exists
  -> no Authorization + needsAuth => 401 challenge
  -> no needsAuth => allow
  -> strategyChain.authenticate(device, request)
       SUCCESS => allow
       FAIL    => 403
       SKIP    => try global password fallback
```

### Legacy kill-switch helper

Extract the current password/global-password authentication code into a helper before introducing strategy-chain logic:

```java
private boolean authenticateWithLegacyPath(SIPRequest request, String password) {
    return ObjectUtils.isEmpty(password)
            || digestHelper.doAuthenticatePlainTextPassword(request, password);
}
```

Required behavior:

- If `identityEnabled=false`, `RegisterRequestProcessor` must not call `strategyChain`.
- If `identityEnabled=false`, existing device password and global-password registration must behave exactly as before.
- If `identityEnabled=false`, `sip_ha1` must be ignored.
- The missing-Authorization 401 challenge behavior must remain identical to the current code when a password/global password is required.

### Helper method

```java
private boolean hasAuthorization(SIPRequest request) {
    return request.getHeader(AuthorizationHeader.NAME) != null;
}
```

---

## 9. Task 6: Configuration

Add or verify these config keys:

```yaml
jxt:
  identity:
    enabled: true
    strategy:
      ha1-enabled: true
      plaintext-enabled: true
```

Notes:

- `jxt.identity.enabled=false` must disable calls to the new strategy-chain integration path and route REGISTER authentication through the legacy password/global-password helper.
- `DeviceAuthStrategyChain` itself should not be conditional on `jxt.identity.enabled`; the kill switch controls behavior in `RegisterRequestProcessor`, not Bean creation.
- `plaintext-enabled=true` is required during migration to avoid breaking legacy GB28181 devices.
- `ha1-enabled=true` is required for IAM-pushed devices.

---

## 10. Task 7: Automated Tests

**Files:**

- Create: `src/test/java/com/genersoft/iot/vmp/jxt/identity/auth/Ha1StrategyTest.java`
- Create: `src/test/java/com/genersoft/iot/vmp/jxt/identity/auth/PlaintextStrategyTest.java`
- Create: `src/test/java/com/genersoft/iot/vmp/jxt/identity/auth/DeviceAuthStrategyChainTest.java`
- Create: `src/test/java/com/genersoft/iot/vmp/gb28181/transmit/event/request/impl/RegisterRequestProcessorAuthTest.java`

### Coverage requirements

```text
CODE PATHS                                             USER FLOWS
[+] Ha1Strategy                                        [+] IAM-pushed terminal REGISTER
  ├── [GAP] sipHa1 null -> SKIP                          ├── [GAP] [->E2E] first REGISTER gets 401
  ├── [GAP] valid digest -> SUCCESS                      ├── [GAP] [->E2E] second REGISTER gets 200
  └── [GAP] invalid digest/exception -> FAIL             └── [GAP] bad digest gets 403
[+] PlaintextStrategy                                  [+] Legacy camera REGISTER
  ├── [GAP] password null -> SKIP                         ├── [GAP] existing password still works
  ├── [GAP] valid password -> SUCCESS                     └── [GAP] wrong password still rejected
  └── [GAP] invalid password/exception -> FAIL
[+] DeviceAuthStrategyChain
  ├── [GAP] priority order respected
  ├── [GAP] first non-SKIP stops chain
  └── [GAP] all SKIP returns SKIP
[+] RegisterRequestProcessor auth block
  ├── [GAP] no auth required -> allow
  ├── [GAP] missing Authorization when required -> 401
  ├── [GAP] HA1 success -> 200 path
  ├── [GAP] HA1 fail -> 403 path
  ├── [GAP] SKIP -> global password fallback
  ├── [GAP] jxt.identity.enabled=false -> legacy auth path
  ├── [GAP] strategyChain not called when kill switch is off
  └── [GAP] Redis cache contains fresh sip_ha1 after W05 sync
```

### Run

```bash
mvn test -pl . -DskipTests=false
```

### Failure modes to cover

| Failure mode | Test required | Expected behavior |
|--------------|---------------|-------------------|
| DB has fresh `sip_ha1` but Redis has stale device | W05 service test + W04 integration test | W05 updates Redis; REGISTER reads fresh HA1. |
| `jxt.identity.enabled=false` | RegisterRequestProcessor auth test | Strategy chain is not called; legacy password/global-password still works. |
| `DeviceAuthStrategyChain` has zero strategies | Chain unit test | Chain returns `SKIP`; caller falls back to global password or no-auth behavior. |
| Missing Authorization with `sip_ha1` | RegisterRequestProcessor auth test | WVP replies 401 challenge. |
| Bad digest with `sip_ha1` | RegisterRequestProcessor auth test | WVP replies 403 wrong password. |
| Legacy password device without `sip_ha1` | RegisterRequestProcessor auth test | Plaintext strategy succeeds. |
| Unknown device with global password disabled | RegisterRequestProcessor auth test | Existing 403 behavior remains. |

---

## 11. Task 8: Manual E2E Test — SIP REGISTER with HA1

### Prerequisites

- W01 + W05 completed.
- Device exists in `wvp_device` with `sip_ha1` populated by IAM.
- Real ZX terminal or SIP test client configured with matching credentials.

### Steps

```text
1. IAM creates equipment and pushes register payload to WVP.
2. Verify WVP DB has `sip_ha1` for device.
3. Terminal sends SIP REGISTER without Authorization.
4. WVP replies 401 challenge.
5. Terminal sends SIP REGISTER with digest Authorization.
6. Ha1Strategy validates digest.
7. WVP replies 200 OK.
8. Device becomes online.
9. WVP publishes DeviceOnlineEvent to IAM.
10. Legacy camera with password still registers successfully.
```

### SQL verification

```sql
SELECT device_id, sip_ha1, on_line
FROM wvp_device
WHERE device_id = '34020000001320000001';
```

---

## 12. Deferred Iteration 3 Prep

These were explicitly excluded from the narrowed W01 + W05 plan and should not be mixed into W04 unless promoted deliberately.

| Item | Description | Notes |
|------|-------------|-------|
| NonceStore | Redis nonce replay protection + three-state fallback | Separate design exists: `2026-05-16-wvp-noncestore-design.md` |
| DisabledStrategy | `device.disabled=true` rejects REGISTER | Needs online stream/INVITE handling decision |
| NotActivatedStrategy | `device.activated=false` rejects REGISTER | Needs product semantics |
| GlobalPasswordStrategy | Extract global password fallback into strategy | Optional cleanup after Phase 1 works |
| HA1 rotation | Add `sip_ha1_previous`, `previous_valid_until`, `Ha1PreviousStrategy` | Must preserve dual-credential window, no direct overwrite |
| Realm fallback | Add `wvp_realm_transition` and 24h old-realm fallback | Requires realm mismatch behavior design |
| Revocation | Add `wvp_revocation_task`, revoke endpoint, worker, SIP BYE | Requires retry/locking/status model |
| HA1 migration | Convert legacy `password` devices to HA1 | Requires migration safety plan |

---

## 13. Optional Task 9: Iteration 3 Schema Prep

> Only do this task if the team explicitly decides to start rotation/revocation work. Do not include it in the minimal W04 strategy-chain implementation.

**Files:**

- Modify or create a later migration script under `数据库/2.7.4/` or the next versioned migration folder.

### Columns for HA1 rotation

```sql
DELIMITER //

CREATE PROCEDURE `wvp_device_identity_rotation_columns`()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE TABLE_SCHEMA = (SELECT DATABASE())
                     AND table_name = 'wvp_device'
                     AND column_name = 'sip_ha1_previous') THEN
        ALTER TABLE wvp_device
            ADD COLUMN sip_ha1_previous VARCHAR(64) DEFAULT NULL COMMENT '轮换双发窗口：旧HA1';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE TABLE_SCHEMA = (SELECT DATABASE())
                     AND table_name = 'wvp_device'
                     AND column_name = 'previous_valid_until') THEN
        ALTER TABLE wvp_device
            ADD COLUMN previous_valid_until DATETIME DEFAULT NULL COMMENT '旧HA1过期时间';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                   WHERE TABLE_SCHEMA = (SELECT DATABASE())
                     AND table_name = 'wvp_device'
                     AND index_name = 'idx_wvp_device_prev_expiry_cover') THEN
        CREATE INDEX idx_wvp_device_prev_expiry_cover
            ON wvp_device(previous_valid_until, sip_ha1_previous);
    END IF;
END; //

CALL wvp_device_identity_rotation_columns();
DROP PROCEDURE wvp_device_identity_rotation_columns;

DELIMITER ;
```

### Revocation task table

```sql
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

CREATE INDEX idx_revocation_task_status
    ON wvp_revocation_task(status, created_at);
```

### Realm transition table

```sql
CREATE TABLE IF NOT EXISTS wvp_realm_transition (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    device_id       VARCHAR(50) NOT NULL COMMENT '设备国标ID',
    old_realm       VARCHAR(64) NOT NULL,
    new_realm       VARCHAR(64) NOT NULL,
    valid_until     DATETIME NOT NULL COMMENT '旧realm fallback截止时间',
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_realm_transition_device
    ON wvp_realm_transition(device_id, valid_until);
```

### Schema prep guardrails

- Do not wire these columns/tables into production logic until the corresponding rotation/revocation tasks are implemented.
- Do not overwrite `sip_ha1` directly during rotation without first preserving the old value and validity window.
- Add cleanup/expiry jobs in the same iteration that starts writing these tables.

---

## 14. File Inventory

| Task | Action | File |
|------|--------|------|
| 1 | Create | `src/.../jxt/identity/auth/AuthResult.java` |
| 1 | Create | `src/.../jxt/identity/auth/DeviceAuthStrategy.java` |
| 2 | Create | `src/.../jxt/identity/auth/Ha1Strategy.java` |
| 3 | Create | `src/.../jxt/identity/auth/PlaintextStrategy.java` |
| 4 | Create | `src/.../jxt/identity/auth/DeviceAuthStrategyChain.java` |
| 5 | Modify | `src/.../gb28181/transmit/event/request/impl/RegisterRequestProcessor.java` |
| 6 | Modify | application config or deployment config |
| 7 | Create | `src/test/.../jxt/identity/auth/Ha1StrategyTest.java` |
| 7 | Create | `src/test/.../jxt/identity/auth/PlaintextStrategyTest.java` |
| 7 | Create | `src/test/.../jxt/identity/auth/DeviceAuthStrategyChainTest.java` |
| 7 | Create | `src/test/.../gb28181/transmit/event/request/impl/RegisterRequestProcessorAuthTest.java` |
| 8 | Manual | SIP REGISTER HA1 E2E verification |
| 9 | Optional | Future migration script for rotation/revocation/realm-transition schema |

---

## 15. Implementation Order

```text
1. Confirm W01 + W05 is deployed and IAM can populate sip_ha1
2. Implement auth interfaces using SIPRequest
3. Implement Ha1Strategy
4. Implement PlaintextStrategy using existing request-based helper
5. Implement DeviceAuthStrategyChain
6. Refactor RegisterRequestProcessor auth block behind feature flag
7. Add automated tests
8. Run compile/tests
9. Run manual HA1 REGISTER E2E
10. Only then consider optional iteration 3 schema prep
```

---

## 16. Worktree Parallelization

Sequential implementation is recommended for production code changes because `RegisterRequestProcessor` is the central integration point.

Safe parallel split:

| Lane | Work | Depends on |
|------|------|------------|
| A | Auth interfaces + `Ha1Strategy` + `PlaintextStrategy` + chain unit tests | W01 + W05 completed |
| B | RegisterRequestProcessor refactor + integration tests | Lane A |
| C | Manual E2E test prep and SIP client fixtures | W01 + W05 completed |
| D | Optional iteration 3 schema prep | Explicit promotion decision |

Execution order:

```text
Lane A and Lane C can start together.
Merge Lane A.
Run Lane B.
Run E2E after Lane B.
Do not run Lane D unless iteration 3 schema prep is explicitly approved.
```

Conflict flags:

- Lane A and Lane B both depend on auth package types. Merge Lane A before editing `RegisterRequestProcessor`.
- Lane D touches migration scripts and should not be mixed with W04 behavior changes.

---

## 17. Rollback

### Soft rollback

```yaml
jxt:
  identity:
    enabled: false
```

Expected behavior:

- WVP falls back to pre-strategy REGISTER auth path if integration is guarded correctly.
- IAM-pushed `sip_ha1` remains in DB but is not used by REGISTER auth.

### Hard rollback

- Revert W04 strategy-chain code.
- Revert `RegisterRequestProcessor` changes.
- Keep W01 + W05 schema/data intact unless explicitly rolling back IAM sync too.

---

## 18. Completion Criteria

- `mvn compile -pl . -q` succeeds.
- Strategy tests pass.
- Register auth regression tests pass.
- Manual HA1 REGISTER E2E succeeds with real terminal or SIP test client.
- Legacy password/global-password REGISTER still works.
- Wrong digest returns 403.
- Missing Authorization returns 401 when auth is required.
- `jxt.identity.enabled=false` reverts to legacy password/global-password auth without requiring code rollback.
- W05 Redis cache refresh is verified before HA1 E2E.
- No changes to W05 IAM register endpoint are required for this plus plan.
