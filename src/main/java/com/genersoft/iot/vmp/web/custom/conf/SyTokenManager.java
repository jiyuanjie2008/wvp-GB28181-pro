package com.genersoft.iot.vmp.web.custom.conf;

/**
 * WVP sy 对接签名凭据的全局持有者。
 * <p>
 * 所有配置字段集中到 {@link #current} 不可变快照，通过 volatile 实现单次原子读取。
 * 写入侧构建新快照并 atomic swap，读取侧在一开始抓取快照局部变量后全部来自该本地引用。
 */
public enum SyTokenManager {
    INSTANCE;

    /** 当前生效的配置快照（null 表示尚未加载成功）。 */
    public volatile SySigningSnapshot current;

}
