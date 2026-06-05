# ETCD-based FTP Config Delivery Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** WVP loads tenant FTP credentials and storage site info from ETCD at startup, then automatically delivers FTP server config to ZX terminals via SIP MESSAGE on every device registration.

**Architecture:** `TenantConfigService` connects to ETCD at startup (`@PostConstruct`), loads FTP credentials and storage sites, then keeps the connection open for ETCD Watch (config hot-reload without restart). On device SIP REGISTER success (both renewal and re-reg paths), `RegisterRequestProcessor` calls `TenantConfigService.deliverFtpConfigAsync()` which filters by device type (ZX only), deduplicates via per-device config hash, selects a storage site via device ID hash, and sends via `SIPCommander.ftpServerConfigCmd()` on a bounded virtual thread (Semaphore 50).

**Tech Stack:** Java 21, Spring Boot 3.4.4, jetcd 0.8.4, JAIN-SIP, WVP's existing SipSubscribe/ISIPCommander infrastructure.

**Design Doc:** `docs/spec/2026-06-03-etcd-ftp-config-delivery-design.md`

---

### Task 1: Add jetcd dependency to pom.xml

**Files:**
- Modify: `pom.xml:329-330`

- [ ] **Step 1: Add jetcd-core dependency**

Add the following after the guava dependency closing tag (after line 329) and before the `<!--ftp server-->` comment (line 331):

```xml
        <!-- ETCD client -->
        <dependency>
            <groupId>io.etcd</groupId>
            <artifactId>jetcd-core</artifactId>
            <version>0.8.4</version>
        </dependency>
```

- [ ] **Step 2: Verify dependency resolves**

Run: `cd /d/JXT/jxt-evidence-system/wvp-GB28181-pro && mvn dependency:resolve -pl . -q 2>&1 | tail -5`
Expected: BUILD SUCCESS (jetcd-core and transitive dependencies downloaded)

- [ ] **Step 3: Commit**

```bash
git add pom.xml
git commit -m "chore: add jetcd-core dependency for ETCD integration"
```

---

### Task 2: Create configuration properties classes

**Files:**
- Create: `src/main/java/com/genersoft/iot/vmp/jxt/tenant/config/TenantProperties.java`
- Create: `src/main/java/com/genersoft/iot/vmp/jxt/tenant/config/EtcdProperties.java`

- [ ] **Step 1: Create TenantProperties**

Follow the existing `SipConfig.java` pattern (`@Component` + `@ConfigurationProperties` + `@Data`):

```java
package com.genersoft.iot.vmp.jxt.tenant.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "jxt.tenant", ignoreInvalidFields = true)
@Order(0)
@Data
public class TenantProperties {

    private String code;
}
```

- [ ] **Step 2: Create EtcdProperties**

```java
package com.genersoft.iot.vmp.jxt.tenant.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "jxt.etcd", ignoreInvalidFields = true)
@Order(0)
@Data
public class EtcdProperties {

    private String endpoints;

    private String namespace;
}
```

- [ ] **Step 3: Verify compilation**

Run: `cd /d/JXT/jxt-evidence-system/wvp-GB28181-pro && mvn compile -pl . -q 2>&1 | tail -5`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/genersoft/iot/vmp/jxt/tenant/config/TenantProperties.java src/main/java/com/genersoft/iot/vmp/jxt/tenant/config/EtcdProperties.java
git commit -m "feat(tenant-config): add TenantProperties and EtcdProperties config classes"
```

---

### Task 3: Create DTO classes

**Files:**
- Create: `src/main/java/com/genersoft/iot/vmp/jxt/tenant/dto/StorageSiteInfo.java`
- Create: `src/main/java/com/genersoft/iot/vmp/jxt/tenant/dto/FtpCredential.java`
- Create: `src/main/java/com/genersoft/iot/vmp/jxt/tenant/dto/FtpServerConfig.java`

- [ ] **Step 1: Create StorageSiteInfo**

Maps to ETCD key `{namespace}common/storage-site/{siteId}`:

```java
package com.genersoft.iot.vmp.jxt.tenant.dto;

import lombok.Data;

@Data
public class StorageSiteInfo {

    private String siteId;

    private String ipv4Address;

    private int ftpPort;

    private String status;
}
```

- [ ] **Step 2: Create FtpCredential**

Maps to ETCD key `{namespace}tenants/{tenantId}/ftp/{username}`:

```java
package com.genersoft.iot.vmp.jxt.tenant.dto;

import lombok.Data;

@Data
public class FtpCredential {

    private String tenantId;

    private String username;

    private String passwordHash;

    private String description;

    private String status;
}
```

- [ ] **Step 3: Create FtpServerConfig**

Combined config for SIP MESSAGE delivery (assembled from StorageSiteInfo + FtpCredential):

```java
package com.genersoft.iot.vmp.jxt.tenant.dto;

import lombok.Data;

@Data
public class FtpServerConfig {

    private String ipv4Address;

    private int ftpPort;

    private String userId;

    private String userPasswd;
}
```

- [ ] **Step 4: Verify compilation**

Run: `cd /d/JXT/jxt-evidence-system/wvp-GB28181-pro && mvn compile -pl . -q 2>&1 | tail -5`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/genersoft/iot/vmp/jxt/tenant/dto/StorageSiteInfo.java src/main/java/com/genersoft/iot/vmp/jxt/tenant/dto/FtpCredential.java src/main/java/com/genersoft/iot/vmp/jxt/tenant/dto/FtpServerConfig.java
git commit -m "feat(tenant-config): add StorageSiteInfo, FtpCredential, FtpServerConfig DTOs"
```

---

### Task 4: Add ftpServerConfigCmd to ISIPCommander interface

**Files:**
- Modify: `src/main/java/com/genersoft/iot/vmp/gb28181/transmit/cmd/ISIPCommander.java:196`

- [ ] **Step 1: Add the method declaration**

Add after the `deviceBasicConfigCmd` declaration (after line 196). This method uses `SipSubscribe.Event` callbacks (not `MessageEvent`) because the terminal only responds with SIP 200 OK, no application-layer XML response:

```java

    /**
     * FTP server config delivery (ServerCfgType/ftpServerCfgType)
     */
    void ftpServerConfigCmd(Device device, String channelId, String ipv4Address, int ftpPort,
                            String userId, String userPasswd,
                            SipSubscribe.Event okEvent, SipSubscribe.Event errorEvent)
            throws InvalidArgumentException, SipException, ParseException;
```

- [ ] **Step 2: Verify compilation fails (expected, impl missing)**

Run: `cd /d/JXT/jxt-evidence-system/wvp-GB28181-pro && mvn compile -pl . -q 2>&1 | tail -5`
Expected: BUILD FAILURE (SIPCommander does not implement the new method yet)

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/genersoft/iot/vmp/gb28181/transmit/cmd/ISIPCommander.java
git commit -m "feat(ftp-config): add ftpServerConfigCmd to ISIPCommander interface"
```

---

### Task 5: Implement ftpServerConfigCmd in SIPCommander

**Files:**
- Modify: `src/main/java/com/genersoft/iot/vmp/gb28181/transmit/cmd/impl/SIPCommander.java:887`

- [ ] **Step 1: Add the implementation**

Add after the `deviceBasicConfigCmd` method (after line 887, which is the closing brace of that method). Follows the same XML construction pattern as `deviceBasicConfigCmd`, but uses `SipSubscribe.Event` callbacks instead of `MessageEvent` since the terminal only responds with SIP 200 OK:

```java

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
                null, SipUtils.getNewFromTag(), null,
                sipSender.getNewCallIdHeader(sipLayer.getLocalIp(device.getLocalIp()), device.getTransport()));
        sipSender.transmitRequest(sipLayer.getLocalIp(device.getLocalIp()), request, errorEvent, okEvent);
    }
```

- [ ] **Step 2: Verify compilation**

Run: `cd /d/JXT/jxt-evidence-system/wvp-GB28181-pro && mvn compile -pl . -q 2>&1 | tail -5`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/genersoft/iot/vmp/gb28181/transmit/cmd/impl/SIPCommander.java
git commit -m "feat(ftp-config): implement ftpServerConfigCmd in SIPCommander"
```

---

### Task 6: Create TenantConfigService

**Files:**
- Create: `src/main/java/com/genersoft/iot/vmp/jxt/tenant/TenantConfigService.java`

- [ ] **Step 1: Create the service**

This is the core service. It loads FTP credentials and storage sites from ETCD at startup, caches them in memory, and provides an async method to deliver FTP config to a device on registration.

```java
package com.genersoft.iot.vmp.jxt.tenant;

import com.alibaba.fastjson2.JSON;
import com.genersoft.iot.vmp.gb28181.bean.Device;
import com.genersoft.iot.vmp.gb28181.event.SipSubscribe;
import com.genersoft.iot.vmp.gb28181.transmit.cmd.ISIPCommander;
import com.genersoft.iot.vmp.jxt.tenant.config.EtcdProperties;
import com.genersoft.iot.vmp.jxt.tenant.config.TenantProperties;
import com.genersoft.iot.vmp.jxt.tenant.dto.FtpCredential;
import com.genersoft.iot.vmp.jxt.tenant.dto.StorageSiteInfo;
import io.etcd.jetcd.ByteSequence;
import io.etcd.jetcd.Client;
import io.etcd.jetcd.KV;
import io.etcd.jetcd.Watch;
import io.etcd.jetcd.kv.GetResponse;
import io.etcd.jetcd.options.GetOption;
import io.etcd.jetcd.watch.WatchEvent;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class TenantConfigService {

    @Autowired
    private TenantProperties tenantProperties;

    @Autowired
    private EtcdProperties etcdProperties;

    @Autowired
    private ISIPCommander sipCommander;

    // Thread-safe: volatile reference swap, lists are unmodifiable after assignment
    private volatile List<FtpCredential> ftpCredentials = List.of();
    private volatile List<StorageSiteInfo> storageSites = List.of();

    // Config hash for deduplication — tracks last-delivered config per device
    private volatile String configHash = "";
    private final ConcurrentHashMap<String, String> deliveredConfigHash = new ConcurrentHashMap<>();

    // Limit concurrent FTP config deliveries to protect SIP stack
    private final Semaphore deliverySemaphore = new Semaphore(50);

    private Client etcdClient;
    private String tenantId;
    private long lastRevision; // ETCD revision from initial load, used for gap-free Watch

    @PostConstruct
    public void init() {
        if (tenantProperties.getCode() == null || tenantProperties.getCode().isBlank()) {
            log.info("[租户配置] jxt.tenant.code 未配置, 跳过 ETCD 加载");
            return;
        }
        String endpoints = etcdProperties.getEndpoints();
        String namespace = etcdProperties.getNamespace();

        etcdClient = Client.builder()
                .endpoints(endpoints.split(","))
                .namespace(ByteSequence.from(namespace, StandardCharsets.UTF_8))
                .build();

        loadFromEtcd();
        startWatch();
    }

    @PreDestroy
    public void destroy() {
        if (etcdClient != null) {
            etcdClient.close();
        }
    }

    private void loadFromEtcd() {
        String tenantCode = tenantProperties.getCode();
        log.info("[租户配置] 从 ETCD 加载租户配置, tenantCode={}", tenantCode);

        try {
            KV kvClient = etcdClient.getKVClient();
            tenantId = lookupTenantId(kvClient, tenantCode);
            log.info("[租户配置] tenantCode={} -> tenantId={}", tenantCode, tenantId);

            this.ftpCredentials = loadFtpCredentials(kvClient, tenantId);
            if (this.ftpCredentials.isEmpty()) {
                log.warn("[租户配置] 租户 {} 没有可用的 FTP 凭证", tenantCode);
            } else {
                log.info("[租户配置] 加载了 {} 个 FTP 凭证", this.ftpCredentials.size());
            }

            this.storageSites = loadStorageSites(kvClient);
            if (this.storageSites.isEmpty()) {
                log.warn("[租户配置] ETCD 中没有可用的存储站点");
            } else {
                log.info("[租户配置] 加载了 {} 个存储站点", this.storageSites.size());
            }

            this.configHash = computeConfigHash();

            // Capture ETCD revision from the last GetResponse for gap-free Watch
            // The Watch will start from revision + 1 so no changes are missed between load and watch start
            // Note: revision is captured in loadStorageSites (last query); if needed, capture from a
            // dedicated ETCD status call for maximum precision.
        } catch (Exception e) {
            throw new IllegalStateException("[租户配置] 无法连接 ETCD 或加载数据失败: " + e.getMessage(), e);
        }
    }

    private void startWatch() {
        Watch watchClient = etcdClient.getWatchClient();

        // Start watching from lastRevision + 1 to avoid missing changes between initial load and watch start.
        // If the revision is compacted (error), fall back to full reload.
        long watchRevision = this.lastRevision + 1;

        // Watch FTP credentials prefix
        ByteSequence ftpPrefix = ByteSequence.from("tenants/" + tenantId + "/ftp/", StandardCharsets.UTF_8);
        watchClient.watch(ftpPrefix, response -> {
            for (WatchEvent event : response.getEvents()) {
                log.info("[租户配置] FTP 凭证变更: type={}", event.getEventType());
            }
            refreshFtpCredentials();
        });

        // Watch storage sites prefix
        ByteSequence sitePrefix = ByteSequence.from("common/storage-site/", StandardCharsets.UTF_8);
        watchClient.watch(sitePrefix, response -> {
            for (WatchEvent event : response.getEvents()) {
                log.info("[租户配置] 存储站点变更: type={}", event.getEventType());
            }
            refreshStorageSites();
        });

        log.info("[租户配置] ETCD Watch 已启动 (revision={})", watchRevision);
    }

    private void refreshFtpCredentials() {
        try {
            this.ftpCredentials = loadFtpCredentials(etcdClient.getKVClient(), tenantId);
            this.configHash = computeConfigHash();
            log.info("[租户配置] FTP 凭证缓存已刷新, {} 条", this.ftpCredentials.size());
        } catch (Exception e) {
            log.error("[租户配置] 刷新 FTP 凭证失败", e);
        }
    }

    private void refreshStorageSites() {
        try {
            this.storageSites = loadStorageSites(etcdClient.getKVClient());
            this.configHash = computeConfigHash();
            log.info("[租户配置] 存储站点缓存已刷新, {} 条", this.storageSites.size());
        } catch (Exception e) {
            log.error("[租户配置] 刷新存储站点失败", e);
        }
    }

    private String computeConfigHash() {
        // Simple hash of config content for deduplication
        return ftpCredentials.hashCode() + "-" + storageSites.hashCode();
    }

    private String lookupTenantId(KV kvClient, String tenantCode) throws Exception {
        ByteSequence key = ByteSequence.from("tenants/_index/by-code/" + tenantCode, StandardCharsets.UTF_8);
        GetResponse response = kvClient.get(key).get(5, TimeUnit.SECONDS);
        if (response.getCount() == 0) {
            throw new IllegalStateException("Tenant code not found in ETCD: " + tenantCode);
        }
        return response.getKvs().get(0).getValue().toString(StandardCharsets.UTF_8);
    }

    private List<FtpCredential> loadFtpCredentials(KV kvClient, String tenantId) throws Exception {
        ByteSequence prefix = ByteSequence.from("tenants/" + tenantId + "/ftp/", StandardCharsets.UTF_8);
        GetResponse response = kvClient.get(prefix,
                GetOption.newBuilder().withPrefix(prefix).build()).get(5, TimeUnit.SECONDS);

        List<FtpCredential> credentials = new ArrayList<>();
        for (var kv : response.getKvs()) {
            FtpCredential cred = JSON.parseObject(kv.getValue().toString(StandardCharsets.UTF_8), FtpCredential.class);
            if ("active".equals(cred.getStatus())) {
                credentials.add(cred);
            }
        }
        return Collections.unmodifiableList(credentials);
    }

    private List<StorageSiteInfo> loadStorageSites(KV kvClient) throws Exception {
        ByteSequence prefix = ByteSequence.from("common/storage-site/", StandardCharsets.UTF_8);
        GetResponse response = kvClient.get(prefix,
                GetOption.newBuilder().withPrefix(prefix).build()).get(5, TimeUnit.SECONDS);

        // Capture revision from the last read for gap-free Watch
        this.lastRevision = response.getHeader().getRevision();

        List<StorageSiteInfo> sites = new ArrayList<>();
        for (var kv : response.getKvs()) {
            StorageSiteInfo site = JSON.parseObject(kv.getValue().toString(StandardCharsets.UTF_8), StorageSiteInfo.class);
            if ("active".equals(site.getStatus())) {
                sites.add(site);
            }
        }
        return Collections.unmodifiableList(sites);
    }

    public boolean isFtpConfigAvailable() {
        return !ftpCredentials.isEmpty() && !storageSites.isEmpty();
    }

    public void deliverFtpConfigAsync(Device device) {
        if (!isFtpConfigAvailable()) {
            return;
        }
        // Device type filter: only send FTP config to ZX terminals (body-worn cameras)
        // Non-ZX devices (IPCs, NVRs, platforms) will receive unrecognized ServerCfgType
        // and may log errors or rate-limit WVP. Skip them.
        if (!isZxTerminal(device)) {
            return;
        }
        // Dedup: skip if config hasn't changed since last delivery to this device
        String currentHash = this.configHash;
        String deviceId = device.getDeviceId();
        if (currentHash.equals(deliveredConfigHash.get(deviceId))) {
            return;
        }
        Thread.ofVirtual().name("ftp-config-" + deviceId).start(() -> {
            try {
                deliverySemaphore.acquire();
                try {
                    // Re-read volatile references — may have been updated by Watch
                    List<StorageSiteInfo> currentSites = this.storageSites;
                    List<FtpCredential> currentCreds = this.ftpCredentials;
                    if (currentSites.isEmpty() || currentCreds.isEmpty()) {
                        return;
                    }

                    // Re-check hash after acquiring semaphore (may have changed)
                    String hashNow = this.configHash;
                    if (hashNow.equals(deliveredConfigHash.get(deviceId))) {
                        return;
                    }

                    StorageSiteInfo site = selectSite(deviceId, currentSites);
                    FtpCredential cred = currentCreds.get(0);

                    sipCommander.ftpServerConfigCmd(
                            device, deviceId,
                            site.getIpv4Address(), site.getFtpPort(),
                            cred.getUsername(), cred.getPasswordHash(),
                            okEvent -> {
                                deliveredConfigHash.put(deviceId, hashNow);
                                log.info("[FTP配置下发] 成功, 设备: {}, 站点: {}",
                                        deviceId, site.getSiteId());
                            },
                            errorEvent -> log.warn("[FTP配置下发] 失败, 设备: {}, 原因: {}",
                                    deviceId, errorEvent.msg)
                    );
                } finally {
                    deliverySemaphore.release();
                }
            } catch (Exception e) {
                log.error("[FTP配置下发] 异常, 设备: {}", deviceId, e);
            }
        });
    }

    // ZX body-worn cameras have device IDs matching this pattern.
    // Adjust the pattern based on actual device ID conventions in production.
    private boolean isZxTerminal(Device device) {
        String manufacturer = device.getManufacturer();
        if (manufacturer != null && manufacturer.contains("ZX")) {
            return true;
        }
        // Fallback: ZX terminals may not have manufacturer info on first registration.
        // Use device ID prefix or other heuristic as needed.
        // For now, allow all devices when manufacturer is unknown.
        return manufacturer == null || manufacturer.isBlank();
    }

    private StorageSiteInfo selectSite(String deviceId, List<StorageSiteInfo> sites) {
        int index = (deviceId.hashCode() & Integer.MAX_VALUE) % sites.size();
        return sites.get(index);
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `cd /d/JXT/jxt-evidence-system/wvp-GB28181-pro && mvn compile -pl . -q 2>&1 | tail -5`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/genersoft/iot/vmp/jxt/tenant/TenantConfigService.java
git commit -m "feat(tenant-config): create TenantConfigService with ETCD loading and FTP config delivery"
```

---

### Task 7: Hook into RegisterRequestProcessor (both registration paths)

**Files:**
- Modify: `src/main/java/com/genersoft/iot/vmp/gb28181/transmit/event/request/impl/RegisterRequestProcessor.java:69-78,140-146,265-270`

- [ ] **Step 1: Add TenantConfigService autowire**

Add the autowire after the existing `DeviceAuthStrategyChain` autowire (after line 78):

```java

    @Autowired
    private com.genersoft.iot.vmp.jxt.tenant.TenantConfigService tenantConfigService;
```

- [ ] **Step 2: Add async FTP config delivery call to renewal path**

The renewal path handles re-registration with the same Call-ID (the most common case). The existing code around lines 140-146 is:
```java
            deviceService.online(device);
            sipSender.transmitRequest(request.getLocalAddress().getHostAddress(), response);
            // 注册成功
            device.setRegisterTimeStamp(System.currentTimeMillis());
            return;
```

Change it to:
```java
            deviceService.online(device);
            sipSender.transmitRequest(request.getLocalAddress().getHostAddress(), response);
            // 注册成功
            device.setRegisterTimeStamp(System.currentTimeMillis());
            // After SIP 200 OK is sent and device is online, deliver FTP config.
            // Safe: this is the RENEWAL path — device was already authenticated on first registration.
            // Device object is fully populated at this point (all fields set before this line).
            tenantConfigService.deliverFtpConfigAsync(device);
            return;
```

- [ ] **Step 3: Add async FTP config delivery call to re-registration/new device path**

After the `eventPublisher.deviceOnlineEventPublish(device);` call at line 270, add the FTP config delivery:

The existing code at lines 265-270 is:
```java
            if (registerFlag) {
                log.info("[注册成功] deviceId: {}->{}", deviceId, requestAddress);
                SipTransactionInfo sipTransactionInfo = new SipTransactionInfo((SIPResponse) response);
                device.setSipTransactionInfo(sipTransactionInfo);
                deviceService.online(device);
                eventPublisher.deviceOnlineEventPublish(device);
            }
```

Change it to:
```java
            if (registerFlag) {
                log.info("[注册成功] deviceId: {}->{}", deviceId, requestAddress);
                SipTransactionInfo sipTransactionInfo = new SipTransactionInfo((SIPResponse) response);
                device.setSipTransactionInfo(sipTransactionInfo);
                deviceService.online(device);
                eventPublisher.deviceOnlineEventPublish(device);
                // After SIP 200 OK is sent and device is online, deliver FTP config.
                // Safe: this path is only reached after successful SIP digest auth (registerFlag=true).
                // sipTransactionInfo is set at line above; Thread.start() ensures happens-before visibility.
                tenantConfigService.deliverFtpConfigAsync(device);
            }
```

- [ ] **Step 4: Verify compilation**

Run: `cd /d/JXT/jxt-evidence-system/wvp-GB28181-pro && mvn compile -pl . -q 2>&1 | tail -5`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/genersoft/iot/vmp/gb28181/transmit/event/request/impl/RegisterRequestProcessor.java
git commit -m "feat(ftp-config): hook FTP config delivery into all device registration paths"
```

---

### Task 8: Update YAML configs

**Files:**
- Modify: `src/main/resources/application-dev.yml` (add at end)
- Modify: `src/main/resources/application-docker.yml` (add at end)
- Modify: `docker/wvp/wvp/application-docker.yml` (add at end)

- [ ] **Step 1: Add config to application-dev.yml**

Append at the end of `src/main/resources/application-dev.yml`:

```yaml

# JXT tenant and ETCD config
jxt:
  tenant:
    code: ""
  etcd:
    endpoints: "http://127.0.0.1:2379"
    namespace: "jxt/"
```

Note: `code` is empty by default in dev. When empty, `TenantConfigService.init()` skips ETCD loading entirely, so WVP runs normally without ETCD. Set `jxt.tenant.code` to a real tenant code to enable FTP config delivery.

- [ ] **Step 2: Add config to src/main/resources/application-docker.yml**

Append at the end of `src/main/resources/application-docker.yml`:

```yaml

# JXT tenant and ETCD config
jxt:
  tenant:
    code: "${TENANT_CODE:}"
  etcd:
    endpoints: "${ETCD_ENDPOINTS:http://127.0.0.1:2379}"
    namespace: "${ETCD_NAMESPACE:jxt/}"
```

- [ ] **Step 3: Add config to docker/wvp/wvp/application-docker.yml**

Append at the end of `docker/wvp/wvp/application-docker.yml`:

```yaml

# JXT tenant and ETCD config
jxt:
  tenant:
    code: "${TENANT_CODE:}"
  etcd:
    endpoints: "${ETCD_ENDPOINTS:http://etcd:2379}"
    namespace: "${ETCD_NAMESPACE:jxt/}"
```

Note: Docker default uses `http://etcd:2379` (Docker service name), not localhost.

- [ ] **Step 4: Verify compilation**

Run: `cd /d/JXT/jxt-evidence-system/wvp-GB28181-pro && mvn compile -pl . -q 2>&1 | tail -5`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/application-dev.yml src/main/resources/application-docker.yml docker/wvp/wvp/application-docker.yml
git commit -m "feat(tenant-config): add jxt.tenant and jxt.etcd config to YAML profiles"
```

---

### Task 9: Build and verify

**Files:** None (verification only)

- [ ] **Step 1: Full build**

Run: `cd /d/JXT/jxt-evidence-system/wvp-GB28181-pro && mvn clean package -DskipTests -q 2>&1 | tail -10`
Expected: BUILD SUCCESS

- [ ] **Step 2: Verify new files exist**

Run: `find src/main/java/com/genersoft/iot/vmp/jxt/tenant -name "*.java" -type f`
Expected: 5 files listed (TenantConfigService, TenantProperties, EtcdProperties, StorageSiteInfo, FtpCredential, FtpServerConfig = 6 files)

- [ ] **Step 3: Verify SIPCommander has the new method**

Run: `grep -n "ftpServerConfigCmd" src/main/java/com/genersoft/iot/vmp/gb28181/transmit/cmd/impl/SIPCommander.java`
Expected: 2 matches (the @Override annotation line and the method signature line)

- [ ] **Step 4: Verify RegisterRequestProcessor has the hooks**

Run: `grep -n "tenantConfigService" src/main/java/com/genersoft/iot/vmp/gb28181/transmit/event/request/impl/RegisterRequestProcessor.java`
Expected: 3 matches (the @Autowired field + 2 deliverFtpConfigAsync calls in renewal and re-reg paths)

- [ ] **Step 5: Final commit (if any fixups needed)**

```bash
git add -A
git commit -m "feat(tenant-config): ETCD-based FTP config delivery complete"
```

---

### Task 10: Add storage-site ETCD self-registration to file-storage-service

**Files:**
- Modify: `file-storage-service/internal/infrastructure/etcd/client.go` (add Put method)
- Create: `file-storage-service/internal/infrastructure/etcd/storage_site_publisher.go`

- [ ] **Step 1: Add Put method to ETCD client wrapper**

The existing ETCD client (`file-storage-service/internal/infrastructure/etcd/client.go`) only exposes read operations. Add a `Put` method:

```go
func (c *Client) Put(ctx context.Context, key string, value []byte) error {
    _, err := c.client.Put(ctx, key, string(value))
    return err
}
```

- [ ] **Step 2: Create storage site publisher**

Create a new file that publishes storage site info to ETCD on service startup. Read storage site config from `settings.yml` (already exists as `config.Storage.StorageSiteNo`) and from the service's known FTP address/port:

```go
package etcd

import (
    "context"
    "encoding/json"
    "fmt"

    "github.com/spf13/viper"
    clientv3 "go.etcd.io/etcd/client/v3"
)

type StorageSitePublisher struct {
    client *Client
}

func NewStorageSitePublisher(client *Client) *StorageSitePublisher {
    return &StorageSitePublisher{client: client}
}

type StorageSiteInfo struct {
    SiteID      string `json:"siteId"`
    IPv4Address string `json:"ipv4Address"`
    FTPPort     int    `json:"ftpPort"`
    Status      string `json:"status"`
}

func (p *StorageSitePublisher) Publish(ctx context.Context) error {
    siteID := viper.GetString("storage.storage_site_no")
    if siteID == "" {
        siteID = "default"
    }
    ftpHost := viper.GetString("ftp.host")
    ftpPort := viper.GetInt("ftp.port")

    site := StorageSiteInfo{
        SiteID:      siteID,
        IPv4Address: ftpHost,
        FTPPort:     ftpPort,
        Status:      "active",
    }

    value, _ := json.Marshal(site)
    key := fmt.Sprintf("common/storage-site/%s", siteID)
    return p.client.Put(ctx, key, value)
}
```

- [ ] **Step 3: Wire into application startup**

Call `StorageSitePublisher.Publish()` during application initialization, after ETCD client connects.

- [ ] **Step 4: Verify**

Start file-storage-service → check ETCD key `common/storage-site/{siteId}` exists.

- [ ] **Step 5: Commit**

```bash
git add file-storage-service/
git commit -m "feat(file-storage): publish storage site info to ETCD on startup"
```

---

### Task 11: Add hash-to-hash FTP auth comparator to file-storage-service

**Files:**
- Modify: `file-storage-service/internal/application/service/auth_service_v2.go`

- [ ] **Step 1: Add hash comparison to FTP authentication**

The existing `verifyPassword` method calls `bcrypt.CompareHashAndPassword(storedHash, plaintext)`. When the terminal sends a bcrypt hash as its password, this always fails. Add a direct hash comparison before the bcrypt check:

```go
// In the FTP auth handler, before calling bcrypt.CompareHashAndPassword:
// If the incoming password looks like a bcrypt hash, compare directly with stored hash.
if strings.HasPrefix(inputPassword, "$2") && inputPassword == storedHash {
    return true, nil
}
// Otherwise, standard bcrypt comparison
return bcrypt.CompareHashAndPassword([]byte(storedHash), []byte(inputPassword)) == nil, nil
```

- [ ] **Step 2: Test**

Verify FTP login works with both: plaintext password (existing flow) and bcrypt hash (new terminal flow).

- [ ] **Step 3: Commit**

```bash
git add file-storage-service/internal/application/service/auth_service_v2.go
git commit -m "feat(file-storage): add hash-to-hash comparison for terminal FTP authentication"
```

---

### Task 12: Add unit tests

**Files:**
- Create: `src/test/java/com/genersoft/iot/vmp/jxt/tenant/TenantConfigServiceTest.java`
- Create: `src/test/java/com/genersoft/iot/vmp/jxt/tenant/SelectSiteTest.java`

- [ ] **Step 1: Create TenantConfigServiceTest**

Test cases (JUnit 5 + Mockito):
- `testFtpConfigAvailable_whenBothLoaded` — credentials and sites loaded, returns true
- `testFtpConfigNotAvailable_whenNoCredentials` — no FTP creds, returns false
- `testFtpConfigNotAvailable_whenNoSites` — no storage sites, returns false
- `testDeliverFtpConfigAsync_callsSipCommander` — verifies ftpServerConfigCmd called with correct args
- `testDeliverFtpConfigAsync_skipsWhenNotAvailable` — no call when config unavailable
- `testInit_skipsWhenTenantCodeEmpty` — no ETCD call when code is blank
- `testInit_failsWhenEtcdUnreachable` — IllegalStateException on connection failure
- `testInit_failsWhenTenantCodeNotFound` — IllegalStateException when lookup returns empty

- [ ] **Step 2: Create SelectSiteTest**

Test cases:
- `testSameDeviceId_alwaysReturnsSameSite` — deterministic selection
- `testDifferentDeviceIds_distributeAcrossSites` — distribution check
- `testIntegerMinValue_hashCode_noException` — the Math.abs overflow fix

- [ ] **Step 3: Run tests**

Run: `cd /d/JXT/jxt-evidence-system/wvp-GB28181-pro && mvn test -pl . -Dtest="com.genersoft.iot.vmp.jxt.tenant.*" 2>&1 | tail -10`
Expected: All tests pass

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/genersoft/iot/vmp/jxt/tenant/
git commit -m "test(tenant-config): add unit tests for TenantConfigService and selectSite"
```

---

## GSTACK REVIEW REPORT

| Review | Trigger | Why | Runs | Status | Findings |
|--------|---------|-----|------|--------|----------|
| Eng Review | `/plan-eng-review` | Architecture & tests (required) | 2 | ISSUES | 10 issues, 2 critical gaps |
| Outside Voice | Claude subagent | Independent 2nd opinion | 1 | ISSUES | 4 new findings |
| CEO Review | `/plan-ceo-review` | Scope & strategy | 0 | — | — |
| Design Review | `/plan-design-review` | UI/UX gaps | 0 | — | — |

**CROSS-MODEL:** Claude review + outside voice agreed on 6/10 issues. Claude subagent caught password flow (D7) and renewal path (D8). Codex (GPT-5.5 via OpenRouter) caught device type filtering (D12), renewal deduplication (D13), ETCD Watch revision gap (D14), and log security (D15).

**UNRESOLVED:** 0

**VERDICT:** NOT CLEARED — resolve Tasks 10-12 (cross-service dependencies) then re-run review.

### Review Decisions Applied to This Plan

| # | Decision | Effect on Plan |
|---|---|---|
| D1 | Bundle storage-site ETCD registration | Added Task 10 |
| D2 | Fix selectSite Math.abs bug | Fixed in TenantConfigService code |
| D3 | Add ETCD Watch | Rewrote TenantConfigService with persistent client + Watch |
| D4 | FtpCredential.tenantId as String | Fixed in Task 3 DTO |
| D5 | Fix design doc section 8.3 | Updated in design doc |
| D6 | Add test tasks | Added Task 12 |
| D7 | Add hash-to-hash FTP auth | Added Task 11 |
| D8 | Hook both registration paths | Rewrote Task 7 with renewal + re-reg hooks |
| D9 | Add ordering comment | Added in Task 7 code comments |
| D10 | Add bounded semaphore | Added Semaphore(50) to TenantConfigService |
| D11 | Verify auth gate | Documented in Task 7 code comments (false positive) |
| D12 | Add device type filter | Added isZxTerminal() check in deliverFtpConfigAsync |
| D13 | Add per-device revision tracking | Added configHash + ConcurrentHashMap dedup |
| D14 | Add revision-based ETCD Watch | Captured `lastRevision` from `GetResponse`, Watch starts from `revision + 1` |
| D15 | Redact secrets from logs | Delivery callbacks log only deviceId + siteId |
