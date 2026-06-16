package com.genersoft.iot.vmp.web.custom.conf;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.genersoft.iot.vmp.jxt.tenant.config.EtcdProperties;
import io.etcd.jetcd.ByteSequence;
import io.etcd.jetcd.Client;
import io.etcd.jetcd.Watch;
import io.etcd.jetcd.options.WatchOption;
import io.etcd.jetcd.watch.WatchEvent;
import io.etcd.jetcd.watch.WatchResponse;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 从 ETCD 单一 Key (jxt/common/wvp-signing) 加载 WVP sy 对接的签名凭据
 * (appKey/appSecret/sm4Key/expiresMin),写入 {@link SyTokenManager}。
 *
 * <p>取代原先从 Redis 读取 SYSTEM_APPKEY / SYSTEM_SM4_KEY 等 4 个 Key 的做法:
 * 把凭据集中到 etcd,与 security-management 共用同一份(Watch 热加载),
 * 一致性由架构保证。镜像 {@code TenantConfigService} 的 jetcd load+watch 模式,
 * 但不被 jxt.tenant.code 门控——签名配置是全局的(位于 common/ 前缀)。
 *
 * <p>adminToken 不进 etcd(WVP 内部专用):首次成功加载时本地生成随机 UUID,
 * 使 SignAuthenticationFilter 的"管理绕过"默认不可用(无人知晓该值)。如需固定
 * 绕过 token,后续可加 WVP-only etcd key。
 */
@Slf4j
@Component
@ConditionalOnProperty(value = "sy.enable", havingValue = "true")
public class SySigningConfigService {

    /** etcd Key(已剥离 jxt/ 命名空间,jetcd 客户端在构建时应用 namespace)。 */
    private static final String SIGNING_KEY = "common/wvp-signing";

    @Autowired
    private EtcdProperties etcdProperties;

    private Client etcdClient;
    private final List<AutoCloseable> activeWatchers = new ArrayList<>();

    /** 加载是否成功(任何一次成功后置 true;DELETE 时保留 true 以沿用最后已知良好值)。 */
    private final AtomicBoolean loaded = new AtomicBoolean(false);

    /** adminToken 是否已生成(只在首次成功 apply 时生成,后续轮换不覆盖)。 */
    private volatile boolean adminTokenGenerated = false;

    private volatile long lastRevision;

    @PostConstruct
    public void init() {
        String endpoints = etcdProperties.getEndpoints();
        String namespace = etcdProperties.getNamespace();
        if (endpoints == null || endpoints.isBlank()) {
            log.warn("[SY签名配置] jxt.etcd.endpoints 未配置, 跳过加载");
            return;
        }
        try {
            etcdClient = Client.builder()
                    .endpoints(endpoints.split(","))
                    .namespace(ByteSequence.from(namespace == null ? "" : namespace, StandardCharsets.UTF_8))
                    .build();
        } catch (Exception e) {
            log.warn("[SY签名配置] 构建 ETCD 客户端失败: {}", e.getMessage());
            return;
        }
        loadFromEtcd();
        startWatch();
    }

    /**
     * 从 ETCD 读取一次签名配置;存在且有效则写入 SyTokenManager 并置 loaded=true。
     * 幂等,可被重试循环反复调用。返回是否成功加载(并应用)。
     */
    public boolean loadFromEtcd() {
        if (etcdClient == null) {
            return false;
        }
        try {
            var resp = etcdClient.getKVClient()
                    .get(ByteSequence.from(SIGNING_KEY, StandardCharsets.UTF_8))
                    .get(5, TimeUnit.SECONDS);
            lastRevision = resp.getHeader().getRevision();
            if (resp.getCount() == 0) {
                return false;
            }
            String value = resp.getKvs().get(0).getValue().toString(StandardCharsets.UTF_8);
            return applyConfigValue(value);
        } catch (Exception e) {
            log.warn("[SY签名配置] 从 ETCD 读取失败: {}", e.getMessage());
            return false;
        }
    }

    /** 是否已成功加载过签名配置(供 CameraChannelService 的启动重试循环判定)。 */
    public boolean isLoaded() {
        return loaded.get();
    }

    private void startWatch() {
        if (etcdClient == null) {
            return;
        }
        Watch watchClient = etcdClient.getWatchClient();
        long watchRevision = lastRevision + 1;
        WatchOption opts = WatchOption.newBuilder().withRevision(watchRevision).build();

        Watch.Watcher watcher = watchClient.watch(
                ByteSequence.from(SIGNING_KEY, StandardCharsets.UTF_8), opts, new Watch.Listener() {
                    @Override
                    public void onNext(WatchResponse response) {
                        for (WatchEvent event : response.getEvents()) {
                            if (event.getEventType() == WatchEvent.EventType.PUT) {
                                String val = event.getKeyValue().getValue().toString(StandardCharsets.UTF_8);
                                applyConfigValue(val);
                            } else if (event.getEventType() == WatchEvent.EventType.DELETE) {
                                // 保留最后已知良好值,不清空 holder(轮换删除窗口内仍可验签)
                                log.warn("[SY签名配置] ETCD Key 被删除, 保留最后已知良好配置");
                            }
                        }
                    }

                    @Override
                    public void onError(Throwable throwable) {
                        log.warn("[SY签名配置] ETCD Watch 错误, 执行重载: {}", throwable.getMessage());
                        loadFromEtcd();
                    }

                    @Override
                    public void onCompleted() {
                        log.warn("[SY签名配置] ETCD Watch 已关闭, 后续轮换需重启或重试");
                    }
                });
        activeWatchers.add(watcher);
        log.info("[SY签名配置] ETCD Watch 已启动 (key={}, revision={})", SIGNING_KEY, watchRevision);
    }

    /** 解析 JSON 并写入 SyTokenManager;无效则跳过(保留旧值)。 */
    private boolean applyConfigValue(String value) {
        try {
            JSONObject obj = JSON.parseObject(value);
            String appKey = obj.getString("appKey");
            String appSecret = obj.getString("appSecret");
            String sm4Key = obj.getString("sm4Key");
            Long expiresMin = obj.getLong("expiresMin");
            if (appKey == null || appKey.isBlank() || appSecret == null || appSecret.isBlank()
                    || sm4Key == null || sm4Key.isBlank()) {
                log.warn("[SY签名配置] ETCD 值不完整, 跳过 (appKey/secret/sm4Key 任一为空)");
                return false;
            }
            synchronized (SyTokenManager.INSTANCE) {
                SyTokenManager.INSTANCE.appMap.clear();
                SyTokenManager.INSTANCE.appMap.put(appKey, appSecret);
                SyTokenManager.INSTANCE.sm4Key = sm4Key;
                SyTokenManager.INSTANCE.expires = (expiresMin != null ? expiresMin : 30L);
                if (!adminTokenGenerated) {
                    SyTokenManager.INSTANCE.adminToken = UUID.randomUUID().toString().replace("-", "");
                    adminTokenGenerated = true;
                }
            }
            loaded.set(true);
            log.info("[SY签名配置] 配置已生效 (appKey={}, source=ETCD, {})",
                    appKey, adminTokenGenerated ? "adminToken=本地随机" : "");
            return true;
        } catch (Exception e) {
            log.warn("[SY签名配置] 解析 ETCD 值失败, 保留旧值: {}", e.getMessage());
            return false;
        }
    }

    @PreDestroy
    public void destroy() {
        for (AutoCloseable w : activeWatchers) {
            try {
                w.close();
            } catch (Exception e) {
                log.warn("[SY签名配置] 关闭 Watcher 失败", e);
            }
        }
        activeWatchers.clear();
        if (etcdClient != null) {
            etcdClient.close();
        }
    }
}
