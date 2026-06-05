# ETCD-based FTP Config Delivery Design

> Date: 2026-06-03
> Status: Draft v1
> Scope: FTP only (Picture/S3 deferred)
> Supersedes: Partially replaces Redis-based desired-state model from `glm-server-config-delivery-spec.md` for FTP config data source

---

## 1. Background

### 1.1 Problem

Each tenant runs a dedicated WVP-GB28181-Pro instance. When a GB28181 terminal (ZX) registers, it needs to receive FTP server configuration so it can upload recorded video files. Currently, WVP has no ETCD integration and no mechanism to automatically deliver FTP config to terminals.

### 1.2 Design Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| FTP credentials source | ETCD (tenant-service) | Tenant FTP config already published to ETCD by tenant-service |
| FTP server address source | ETCD (storage-service self-registration) | FTP server is shared infrastructure, auto-registered on startup |
| Trigger timing | SIP REGISTER | Terminal gets config on every registration/startup |
| Data granularity | Per-tenant (not per-device) | All devices under a tenant share the same FTP credentials |
| Password format | bcrypt hash | Terminal stores as-is; file-storage-service adds hash-to-hash comparison |
| Load balancing | Device ID hash | Same device always maps to same FTP server; files not scattered |
| Dynamic config update | ETCD Watch | Config changes propagate in real-time without restart; Watch on `tenants/{id}/ftp/` and `common/storage-site/` prefixes |
| Picture/S3 config | Deferred | Not needed in this phase |

### 1.3 System Context

```
┌─────────────────┐     ┌─────────────┐     ┌──────────────────────┐
│  tenant-service  │────▶│    ETCD      │◀────│  storage-service     │
│  (Go)            │     │             │     │  (FTP server)        │
│  Publishes FTP   │     │  Tenant FTP │     │  Self-registers      │
│  credentials     │     │  credentials│     │  address + port      │
└─────────────────┘     │             │     └──────────────────────┘
                         │  Storage    │
                         │  site info  │
                         └──────┬──────┘
                                │ WVP reads at startup + Watch
                                ▼
                         ┌──────────────────────┐
                         │  WVP-GB28181-Pro      │
                         │  (Java, per-tenant)   │
                         │                       │
                         │  1. Load from ETCD    │
                         │  2. Cache in memory   │
                         │  3. On SIP REGISTER   │
                         │     → select FTP site │
                         │     → build XML       │
                         │     → SIP MESSAGE     │
                         └──────────┬────────────┘
                                    │ SIP MESSAGE
                                    ▼
                         ┌──────────────────────┐
                         │  ZX Terminal          │
                         │  Parses XML           │
                         │  Stores FTP config    │
                         │  Starts upload thread │
                         └──────────────────────┘
```

---

## 2. Data Models

### 2.1 ETCD Keys

**Storage site (global, written by storage-service on startup):**

```
Key:   {namespace}common/storage-site/{siteId}
Value: {
  "siteId": "ftp-site-001",
  "ipv4Address": "192.168.0.40",
  "ftpPort": 21,
  "status": "active"
}
```

**Tenant FTP credentials (per-tenant, written by tenant-service):**

```
Key:   {namespace}tenants/_index/by-code/{tenantCode}
Value: {tenantId}

Key:   {namespace}tenants/{tenantId}/ftp/{username}
Value: {
  "tenantId": 1,
  "username": "ftp_user_001",
  "passwordHash": "$2a$10$xxxx...",
  "description": "执法记录仪FTP",
  "status": "active"
}
```

### 2.2 WVP Configuration (application.yml)

```yaml
jxt:
  tenant:
    code: "tenant-001"              # Tenant code, used to look up tenantId in ETCD
  etcd:
    endpoints: "http://etcd:2379"   # ETCD address
    namespace: "jxt/"               # ETCD namespace, must match tenant-service
```

### 2.3 WVP Internal Data Models

```java
// Storage site info (from ETCD common/storage-site/)
public class StorageSiteInfo {
    private String siteId;
    private String ipv4Address;
    private int ftpPort;
    private String status;
}

// Tenant FTP credential (from ETCD tenants/{id}/ftp/{username})
public class FtpCredential {
    private String tenantId;       // Tenant ID (unused in logic, for traceability)
    private String username;       // FTP login username
    private String passwordHash;   // bcrypt hash, sent as-is to terminal
    private String description;    // Description (e.g., "执法记录仪FTP")
    private String status;         // "active" or "inactive"; only active credentials are loaded
}

// Combined config for SIP MESSAGE delivery (optional — TenantConfigService can also
// pass fields directly to SIPCommander without assembling this DTO first)
public class FtpServerConfig {
    private String ipv4Address;    // From StorageSiteInfo
    private int ftpPort;           // From StorageSiteInfo
    private String userId;         // From FtpCredential.username
    private String userPasswd;     // From FtpCredential.passwordHash (bcrypt hash)
}
```

---

## 3. SIP MESSAGE XML Format

### 3.1 FTP Config XML

```xml
<?xml version="1.0"?>
<Control>
  <CmdType>ServerCfgType</CmdType>
  <SN>{sn}</SN>
  <DeviceID>{deviceId}</DeviceID>
  <ServerType>ftpServerCfgType</ServerType>
  <FtpServerCfgType>
    <Ipv4Address>192.168.0.40</Ipv4Address>
    <FTPPort>21</FTPPort>
    <UserId>ftp_user_001</UserId>
    <UserPasswd>$2a$10$xxxx...</UserPasswd>
  </FtpServerCfgType>
</Control>
```

### 3.2 Terminal Parsing (verified from ZX source code)

| XML Node | Terminal Target | Null Safety |
|----------|----------------|-------------|
| `FtpServerCfgType/Ipv4Address` | `FTPService.setFtpserviceIp()` | Required (NPE if missing) |
| `FtpServerCfgType/FTPPort` | `FTPService.setFtpservicePort()` | Required (NPE if missing) |
| `FtpServerCfgType/UserId` | `FTPService.setFtpserviceName()` | Required (NPE if missing) |
| `FtpServerCfgType/UserPasswd` | `FTPService.setFTPServicePWD()` | Required (NPE if missing) |

Terminal sets `FTPService.setIsReceive(true)` after parsing, which wakes up the upload thread.

### 3.3 Constraints

- CmdType must be exactly `ServerCfgType` (case-insensitive match in terminal)
- ServerType must be exactly `ftpServerCfgType` (case-sensitive match in terminal)
- Root element is `<Control>`
- All four FtpServerCfgType child elements are mandatory (terminal will crash if any is missing)
- Terminal only accepts ONE FtpServerCfgType per message (if/else structure in parser)
- No `<Version>` element needed (terminal does not parse it)
- SIP Content-Type: `Application/MANSCDP+xml`
- Terminal responds with SIP 200 OK only (no application-layer XML response)

---

## 4. ETCD Loading Flow

### 4.1 Startup Sequence

`TenantConfigService` (`@PostConstruct`):

```
1. Connect to ETCD using jetcd client (persistent, kept open for Watch)
2. Read {namespace}tenants/_index/by-code/{tenantCode} → tenantId
3. Prefix query {namespace}tenants/{tenantId}/ftp/ → List<FtpCredential>
   - Filter status=active only
4. Prefix query {namespace}common/storage-site/ → List<StorageSiteInfo>
   - Filter status=active only
5. Validate: at least one FTP credential and one storage site must exist
6. Cache in memory (volatile fields, unmodifiable lists)
7. Compute config hash for deduplication
8. Start ETCD Watch on FTP credentials and storage site prefixes (from initial load revision + 1)
9. Keep ETCD connection open; close on @PreDestroy
```

### 4.2 Failure Handling

| Scenario | Behavior |
|----------|----------|
| ETCD unreachable | WVP startup fails with clear error message |
| Tenant code not found in ETCD | WVP startup fails with clear error message |
| No FTP credentials for tenant | WVP starts but logs WARN; no FTP config sent on device register |
| No storage sites in ETCD | WVP starts but logs WARN; no FTP config sent on device register |

### 4.3 ETCD Dependency

- jetcd (io.etcd:jetcd-core) added to pom.xml
- Persistent ETCD connection for Watch on FTP credentials and storage site prefixes
- Initial load at startup, then Watch for config changes
- `@PreDestroy` closes ETCD client on shutdown
- ETCD credentials: none (internal network)

---

## 5. FTP Server Selection (Load Balancing)

### 5.1 Strategy: Device ID Consistent Hash

```java
StorageSiteInfo selectSite(String deviceId, List<StorageSiteInfo> sites) {
    int index = (deviceId.hashCode() & Integer.MAX_VALUE) % sites.size();
    return sites.get(index);
}
```

### 5.2 Properties

- Same device ID always maps to the same storage site
- Files from the same device are not scattered across multiple servers
- When sites change (via ETCD Watch), remapping is possible but acceptable — devices get the new site assignment on next registration

---

## 6. Trigger and Delivery Flow

### 6.1 Flow Diagram

```
ZX Terminal                     WVP                             ETCD
    │                            │                               │
    │── SIP REGISTER ──────────▶│                               │
    │                            │                               │
    │◀── SIP 200 OK ───────────│                               │
    │                            │                               │
    │                            │ (async, does not block 200 OK)│
    │                            │                               │
    │                            │ 1. Hash deviceId → select site │
    │                            │ 2. Get tenant FTP credential   │
    │                            │ 3. Build ServerCfgType XML     │
    │                            │                               │
    │◀── SIP MESSAGE ──────────│                               │
    │    (ServerCfgType XML)     │                               │
    │                            │                               │
    │── SIP 200 OK ────────────▶│                               │
    │                            │                               │
    │ (Parse XML, store config,  │                               │
    │  wake upload thread)       │                               │
```

### 6.2 Integration Point

- Hook into **both** registration success paths in `RegisterRequestProcessor`:
  - **Renewal path** (same Call-ID, most common): after `deviceService.online()` and SIP 200 OK sent
  - **Re-registration / new device path**: after `deviceService.online()` and `eventPublisher.deviceOnlineEventPublish()`
- Both hook points are after successful authentication — FTP config is never sent to unauthenticated devices
- Device type filter: only deliver to ZX terminals (`isZxTerminal()` check). Non-ZX devices (IPCs, NVRs) are skipped to avoid sending unrecognized ServerCfgType messages
- Call `SIPCommander.ftpServerConfigCmd()` asynchronously (Virtual Thread with bounded Semaphore)
- Do not block the SIP REGISTER 200 OK response
- **Deduplication**: per-device config hash tracking prevents redundant delivery on every 60-second renewal. Only sends when config actually changes or on first registration

### 6.3 SipSubscribe Pattern

- Use WVP's existing `SipSubscribe` pattern (same as PTZ control `fronEndCmd`)
- `okEvent` callback on SIP 200 OK → log success
- `errorEvent` callback on SIP error → log warning
- 5-second timeout → log warning, no retry (next registration will retry)

---

## 7. Password Handling

### 7.1 Flow

```
tenant-service creates FTP config
  → stores bcrypt hash in ETCD
    → WVP reads hash from ETCD
      → sends hash as UserPasswd in SIP MESSAGE
        → terminal stores hash in FTPService
          → terminal uses hash as password when connecting to FTP
            → file-storage-service compares hash directly with stored hash
```

### 7.2 file-storage-service Change (required dependency)

- Add new authentication method: accept bcrypt hash directly and compare with stored hash
- **Why this is necessary:** Standard `bcrypt.CompareHashAndPassword(stored_hash, plaintext)` expects the second argument to be plaintext. The terminal sends the bcrypt hash as its FTP password. Calling `bcrypt.CompareHashAndPassword(stored_hash, hash)` always returns false because bcrypt is comparing a hash against a hash.
- **Fix:** Add a direct string comparison path: if the incoming password looks like a bcrypt hash (`$2a$...`), compare it directly with the stored hash. Otherwise, fall through to the standard bcrypt comparison.
- Existing plaintext → bcrypt → compare method remains as fallback

---

## 8. Code Changes Summary

### 8.1 New Files

| File | Purpose |
|------|---------|
| `jxt/tenant/TenantConfigService.java` | ETCD loading, caching, FTP config assembly |
| `jxt/tenant/config/TenantProperties.java` | `@ConfigurationProperties(prefix = "jxt.tenant")` |
| `jxt/tenant/config/EtcdProperties.java` | `@ConfigurationProperties(prefix = "jxt.etcd")` |
| `jxt/tenant/dto/StorageSiteInfo.java` | Storage site data model |
| `jxt/tenant/dto/FtpCredential.java` | FTP credential data model |
| `jxt/tenant/dto/FtpServerConfig.java` | Combined config for SIP MESSAGE |

### 8.2 Modified Files

| File | Change |
|------|--------|
| `pom.xml` | Add jetcd dependency |
| `application-dev.yml` | Add `jxt.tenant.code` and `jxt.etcd.*` config |
| `application-docker.yml` | Add same config with env var defaults |
| `ISIPCommander.java` | Add `ftpServerConfigCmd()` method declaration |
| `SIPCommander.java` | Implement `ftpServerConfigCmd()` |
| `RegisterRequestProcessor.java` | Add async FTP config delivery to both renewal and re-registration success paths |

### 8.3 Existing Code to Reuse

The `deviceBasicConfigCmd` method in `SIPCommander.java` (lines 845-887) provides the pattern for XML construction and SIP MESSAGE sending. The `ftpServerConfigCmd` method must be created from scratch — the prior plan (`2026-04-28-ftp-config-delivery.md`) was never implemented. The XML construction follows the same StringBuffer pattern as `deviceBasicConfigCmd`, but uses `SipSubscribe.Event` callbacks instead of `MessageEvent` since the terminal only responds with SIP 200 OK.

---

## 9. Differences from Existing Spec (glm-server-config-delivery-spec.md)

| Dimension | Existing Spec (v3 Redis) | This Design (ETCD) |
|-----------|-------------------------|---------------------|
| Data source | Redis (written by security-management) | ETCD (tenant-service + storage-service) |
| Granularity | Per-device config | Per-tenant config |
| Version tracking | desired/delivered with version numbers | Config hash dedup (per-device tracking to avoid redundant sends) |
| Backoff | Error count + 5min backoff window | None (simple retry on next registration) |
| Admin API | Forced refresh endpoint | Not included in this phase |
| FTP server | Single, specified by security-management | Multiple, hash-based selection |
| Password | Plaintext | bcrypt hash |
| Picture/S3 | Supported | Deferred |

---

## 9.5 Security Considerations

- **Log redaction**: Delivery callbacks only log `deviceId` and `siteId`. Never log `userId` or `passwordHash`. SIP debug logging must be configured to suppress MESSAGE bodies for `ServerCfgType` commands to prevent credential exposure in log files.
- **Authentication gate**: FTP config delivery hooks are placed after successful SIP digest authentication in `RegisterRequestProcessor`. Unauthenticated devices never receive FTP credentials.
- **Device type filtering**: Only ZX terminals receive FTP config. Non-ZX devices (IPCs, NVRs, platforms) are filtered out via `isZxTerminal()` check to prevent sending unrecognized vendor extensions.

---

## 10. Out of Scope

- Picture/S3 server config delivery (future phase)
- Per-device version tracking and desired/delivered model
- Admin forced-refresh API
- Metrics and tracing (add later if needed)
- tenant-service changes (FTP config schema already exists)

## 10.1 Cross-Service Dependencies (Required Before Ship)

These tasks are in scope for the overall feature but live in other services:

- **file-storage-service ETCD self-registration** — Publish `common/storage-site/{siteId}` to ETCD on startup. The WVP FTP config delivery depends on this data existing in ETCD.
- **file-storage-service hash-to-hash FTP auth** — Add authentication method that accepts bcrypt hash directly and compares with stored hash. Standard `bcrypt.CompareHashAndPassword(stored_hash, plaintext)` won't work when the terminal sends a hash.

---

## 11. Testing

### 11.1 ETCD Loading

- Verify WVP reads tenant FTP credentials correctly
- Verify WVP reads storage site list correctly
- Verify startup fails gracefully when ETCD is unreachable
- Verify startup fails when tenant code not found

### 11.2 FTP Server Selection

- Same device ID always returns same storage site
- Different device IDs distribute across available sites

### 11.3 SIP MESSAGE

- XML structure matches terminal parser expectations
- All four FTP fields are present and correctly mapped
- SIP MESSAGE is sent after REGISTER 200 OK, not blocking registration
- Terminal receives and parses config correctly (integration test with real device)

### 11.4 Edge Cases

- No FTP credentials for tenant → skip delivery, log WARN
- No storage sites → skip delivery, log WARN
- Device offline after register → SIP MESSAGE timeout, log WARN
- Multiple FTP credentials → select first active one

---

## GSTACK REVIEW REPORT

| Review | Trigger | Why | Runs | Status | Findings |
|--------|---------|-----|------|--------|----------|
| Eng Review | `/plan-eng-review` | Architecture & tests (required) | 2 | ISSUES | 10 issues, 2 critical gaps |
| Outside Voice | Claude subagent | Independent 2nd opinion | 1 | ISSUES | 4 new findings (password flow, renewal path, concurrency, race condition) |
| CEO Review | `/plan-ceo-review` | Scope & strategy | 0 | — | — |
| Design Review | `/plan-design-review` | UI/UX gaps | 0 | — | — |

**CROSS-MODEL:** Claude review + outside voice (Claude subagent) agreed on 6/10 issues. Second outside voice (Codex/GPT-5.5 via OpenRouter) found 5 additional issues: device type filtering (D12), renewal deduplication (D13), ETCD Watch revision gap (D14), log security (D15), and auth gate verification (D11, false positive).

**UNRESOLVED:** 0 — all 10 decisions resolved with user input.

**CRITICAL GAPS (2):**
1. FTP authentication chain broken — `bcrypt.CompareHashAndPassword(hash, hash)` always fails. Requires hash-to-hash comparator in file-storage-service.
2. Storage site ETCD data doesn't exist — no service writes `common/storage-site/{siteId}` to ETCD. Requires file-storage-service ETCD self-registration.

**VERDICT:** NOT CLEARED — 6 P1 tasks must be resolved before ship. See implementation plan for task details.
