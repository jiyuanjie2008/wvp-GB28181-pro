package com.genersoft.iot.vmp.web.custom.conf;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 不可变配置快照：一次原子读取获取全部签名配置。
 * 密钥轮换时构建新快照并原子替换引用，确保读取线程始终看到一致状态。
 */
public record SySigningSnapshot(
        Map<String, String> appMap,
        String sm4Key,
        Long expires,
        String adminToken
) {
    public SySigningSnapshot {
        appMap = Collections.unmodifiableMap(new HashMap<>(appMap));
    }
}
