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
| Dynamic config update | Restart WVP | FTP config rarely changes; WVP already uses static config pattern |
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
                                │ WVP reads at startup
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
    private String username;       // FTP login username
    private String passwordHash;   // bcrypt hash, sent as-is to terminal
}

// Combined config for SIP MESSAGE delivery
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
1. Connect to ETCD using jetcd client
2. Read {namespace}tenants/_index/by-code/{tenantCode} → tenantId
3. Prefix query {namespace}tenants/{tenantId}/ftp/ → List<FtpCredential>
   - Filter status=active only
4. Prefix query {namespace}common/storage-site/ → List<StorageSiteInfo>
   - Filter status=active only
5. Validate: at least one FTP credential and one storage site must exist
6. Cache in memory (Spring Bean)
7. Close ETCD connection
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
- One-time read at startup, no watch, no persistent connection
- ETCD credentials: none (internal network)

---

## 5. FTP Server Selection (Load Balancing)

### 5.1 Strategy: Device ID Consistent Hash

```java
StorageSiteInfo selectSite(String deviceId, List<StorageSiteInfo> sites) {
    int index = Math.abs(deviceId.hashCode()) % sites.size();
    return sites.get(index);
}
```

### 5.2 Properties

- Same device ID always maps to the same storage site
- Files from the same device are not scattered across multiple servers
- When sites change (WVP restart with updated ETCD data), remapping is possible but acceptable

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

- Hook into existing device registration success handler (same point as `RegisterResponseProcessor`)
- Call `SIPCommander.ftpServerConfigCmd()` asynchronously (Virtual Thread)
- Do not block the SIP REGISTER 200 OK response

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

### 7.2 file-storage-service Change (out of WVP scope)

- Add new authentication method: accept bcrypt hash directly and compare with stored hash
- Existing plaintext → bcrypt → compare method remains as fallback
- Both methods are tried: if direct hash comparison matches, authenticate; otherwise try plaintext → bcrypt

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
| Device registration handler | Add async call to FTP config delivery on register success |

### 8.3 Existing Code to Reuse

The existing `ftpServerConfigCmd` implementation in `SIPCommander.java` (from `2026-04-28-ftp-config-delivery.md` plan) can be reused as-is. The XML construction and SIP sending logic are identical. What changes is the **data source** (ETCD instead of Redis/HTTP) and the **trigger** (SIP REGISTER instead of REST API).

---

## 9. Differences from Existing Spec (glm-server-config-delivery-spec.md)

| Dimension | Existing Spec (v3 Redis) | This Design (ETCD) |
|-----------|-------------------------|---------------------|
| Data source | Redis (written by security-management) | ETCD (tenant-service + storage-service) |
| Granularity | Per-device config | Per-tenant config |
| Version tracking | desired/delivered with version numbers | None (simplified) |
| Backoff | Error count + 5min backoff window | None (simple retry on next registration) |
| Admin API | Forced refresh endpoint | Not included in this phase |
| FTP server | Single, specified by security-management | Multiple, hash-based selection |
| Password | Plaintext | bcrypt hash |
| Picture/S3 | Supported | Deferred |

---

## 10. Out of Scope

- Picture/S3 server config delivery (future phase)
- ETCD Watch for dynamic config updates (restart WVP instead)
- Per-device version tracking and desired/delivered model
- Admin forced-refresh API
- Metrics and tracing (add later if needed)
- tenant-service changes (FTP config schema already exists)
- storage-service ETCD self-registration (separate task)
- file-storage-service bcrypt hash authentication (separate task)

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
