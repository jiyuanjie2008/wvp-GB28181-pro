package com.genersoft.iot.vmp.jxt.tenant;

import com.alibaba.fastjson2.JSON;
import com.genersoft.iot.vmp.gb28181.bean.Device;
import com.genersoft.iot.vmp.gb28181.event.device.DeviceOfflineEvent;
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
import io.etcd.jetcd.options.WatchOption;
import io.etcd.jetcd.watch.WatchEvent;
import io.etcd.jetcd.watch.WatchResponse;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
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
    private static final int MAX_DELIVERED_HASH_ENTRIES = 10_000;
    private final ConcurrentHashMap<String, String> deliveredConfigHash = new ConcurrentHashMap<>();

    // Limit concurrent FTP config deliveries to protect SIP stack
    private final Semaphore deliverySemaphore = new Semaphore(50);

    private Client etcdClient;
    private final List<AutoCloseable> activeWatchers = new ArrayList<>();
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
        // Close watchers first to cancel watch subscriptions
        for (AutoCloseable watcher : activeWatchers) {
            try {
                watcher.close();
            } catch (Exception e) {
                log.warn("[租户配置] 关闭 Watcher 失败", e);
            }
        }
        activeWatchers.clear();
        if (etcdClient != null) {
            etcdClient.close();
        }
    }

    @EventListener
    public void onDeviceOffline(DeviceOfflineEvent event) {
        if (event.getDeviceIds() != null && !event.getDeviceIds().isEmpty()) {
            for (String deviceId : event.getDeviceIds()) {
                deliveredConfigHash.remove(deviceId);
            }
        } else if (deliveredConfigHash.size() > MAX_DELIVERED_HASH_ENTRIES) {
            // Safety net: event without device IDs + cache oversized → full clear
            log.info("[租户配置] 设备离线事件无设备列表且去重缓存过大({}), 执行清理",
                    deliveredConfigHash.size());
            deliveredConfigHash.clear();
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

        WatchOption watchOpts = WatchOption.newBuilder().withRevision(watchRevision).build();

        // Watch FTP credentials prefix
        ByteSequence ftpPrefix = ByteSequence.from("tenants/" + tenantId + "/ftp/", StandardCharsets.UTF_8);
        var ftpWatcher = watchClient.watch(ftpPrefix, watchOpts, new Watch.Listener() {
            @Override
            public void onNext(WatchResponse response) {
                for (WatchEvent event : response.getEvents()) {
                    log.info("[租户配置] FTP 凭证变更: type={}", event.getEventType());
                }
                refreshFtpCredentials();
            }

            @Override
            public void onError(Throwable throwable) {
                log.error("[租户配置] FTP Watch 错误, 执行全量重载", throwable);
                refreshFtpCredentials();
            }

            @Override
            public void onCompleted() {
                log.warn("[租户配置] FTP Watch 已关闭, 后续配置变更将不会自动刷新");
            }
        });
        activeWatchers.add(ftpWatcher);

        // Watch storage sites prefix
        ByteSequence sitePrefix = ByteSequence.from("common/storage-site/", StandardCharsets.UTF_8);
        var siteWatcher = watchClient.watch(sitePrefix, watchOpts, new Watch.Listener() {
            @Override
            public void onNext(WatchResponse response) {
                for (WatchEvent event : response.getEvents()) {
                    log.info("[租户配置] 存储站点变更: type={}", event.getEventType());
                }
                refreshStorageSites();
            }

            @Override
            public void onError(Throwable throwable) {
                log.error("[租户配置] 存储 Watch 错误, 执行全量重载", throwable);
                refreshStorageSites();
            }

            @Override
            public void onCompleted() {
                log.warn("[租户配置] 存储 Watch 已关闭, 后续配置变更将不会自动刷新");
            }
        });
        activeWatchers.add(siteWatcher);

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
        String rawValue = response.getKvs().get(0).getValue().toString(StandardCharsets.UTF_8);
        // The index value may be a plain ID or a JSON object like {"code":"default","id":1,"name":"默认租户"}
        if (rawValue.startsWith("{")) {
            com.alibaba.fastjson2.JSONObject obj = JSON.parseObject(rawValue);
            return String.valueOf(obj.getIntValue("id"));
        }
        return rawValue;
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
        // Device type filter: only send FTP config to BWC (body-worn camera) devices.
        // Other device types should not receive FTP config.
        if (!isBwcDevice(device)) {
            return;
        }
        // Terminal C++ TCP path passes NULL szMsg to gb28181_control_rx else branch,
        // causing strstr(NULL,"<?xml") → SIGSEGV crash. Only deliver via UDP.
        if ("TCP".equalsIgnoreCase(device.getTransport())) {
            log.warn("[FTP配置下发] 跳过TCP设备(终端会崩溃), 设备: {}", device.getDeviceId());
            return;
        }
        // Dedup: skip if config hasn't changed since last successful delivery to this device
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
                        log.warn("[FTP配置下发] 跳过, 配置不完整, 设备: {}", deviceId);
                        return;
                    }

                    StorageSiteInfo site = selectSite(deviceId, currentSites);
                    FtpCredential cred = currentCreds.get(0);

                    sipCommander.ftpServerConfigCmd(
                            device, deviceId,
                            site.getIpv4Address(), site.getFtpPort(),
                            cred.getUsername(), cred.getPasswordHash(),
                            okEvent -> {
                                if (deliveredConfigHash.size() > MAX_DELIVERED_HASH_ENTRIES) {
                                    log.info("[FTP配置下发] 去重缓存超过 {} 条, 执行清理",
                                            MAX_DELIVERED_HASH_ENTRIES);
                                    deliveredConfigHash.clear();
                                }
                                deliveredConfigHash.put(deviceId, currentHash);
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

    private boolean isBwcDevice(Device device) {
        return "BWC".equals(device.getDeviceType());
    }

    private StorageSiteInfo selectSite(String deviceId, List<StorageSiteInfo> sites) {
        int index = (deviceId.hashCode() & Integer.MAX_VALUE) % sites.size();
        return sites.get(index);
    }
}
