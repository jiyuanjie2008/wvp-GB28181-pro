# 设计方案：WVP-GB28181-Pro 多租户架构

生成时间：2026-05-02
分支：master
仓库：jiyuanjie2008/wvp-GB28181-Pro
状态：DRAFT

## 问题描述

JXT 证据系统需要多租户 GB28181 视频监控能力。当前 WVP-GB28181-Pro 是单身份架构：只有一个 `sip.id`、一个 `sip.domain`、一个 `sip.port`。每个租户（执法机构）需要独立的 SIP 身份，使其摄像头注册到自己的逻辑 GB28181 服务器，同时共享基础设施以降低资源成本。

## 约束条件（来自代码分析）

1. **WVP 硬编码了单一 SIP 身份** — 10 个文件中 40+ 处通过 `sipConfig.getId()` / `sipConfig.getDomain()` 引用
2. **SipLayer 每个 IP 创建一个 SipStack** — 所有栈共享同一端口和身份
3. **ZLM Hook 是单目标的** — 但由于只有一个 WVP，这不是问题
4. **Device 表没有 tenant_id 列**
5. **流 ID 遵循约定格式** `{deviceId}_{channelId}`，没有租户命名空间
6. **Redis 键是全局的**（设备缓存、SSRC 池、会话管理）

## 选定方案

**B2：ETCD/Redis 驱动的多租户 SIP 身份**

- 所有租户共享一个 WVP 实例，使用一个 SIP 端口（5060）
- 每个租户拥有独立的 SIP 服务器编码、域名和密码
- 租户 SIP 配置从 ETCD 加载（通过已有的 tenant-service）
- SIP 身份按设备动态确定，而非全局固定
- ZLM 由所有租户共享（一个 WVP = 一个 ZLM 目标）

### 为什么选择 B2

| 备选方案 | 未选原因 |
|---------|---------|
| A：每租户独立部署 WVP+ZLM | 运维成本随租户线性增长，50 个租户 = 50 套容器 |
| B1：数据库驱动配置 | JXT 已有 tenant-service + ETCD，再加数据库表是冗余的 |
| 每租户独立端口 | NAT/防火墙复杂度高，对 GB28181 没有实际收益 |

## 前提假设

1. **所有租户可以共享 SIP 端口 5060。** GB28181 设备通过 SIP 域名和服务器编码（而非端口）识别其服务器。单端口模式可行。
2. **从 Request-URI 中的 SIP domain 确定租户身份。** 设备 REGISTER 时，`sip:<serverId>@<domain>` 中的 domain 部分指示设备属于哪个租户。
3. **设备与租户的映射关系是稳定的。** 一个设备属于一个租户，不会切换。
4. **上游 WVP 版本合并可以管理。** 多租户改造应集中在独立模块中，减少与上游更新的冲突。

## 架构设计

### 数据流

```
设备 REGISTER 请求
    │
    ▼
SipLayer (端口 5060，单个 SipStack)
    │
    ▼
RegisterRequestProcessor
    │ 从 Request-URI 提取 domain
    │ 通过 domain 查找 TenantSipConfig
    │ 使用租户的 password/realm 进行摘要认证
    │ 设置 device.tenantId
    ▼
设备信息存入数据库和 Redis（带 tenant_id）
    │
    ▼
SIPRequestHeaderProvider.createXxxRequest(device)
    │ 通过 device.tenantId 查找 TenantSipConfig
    │ 使用租户的 sipId/sipDomain 构建 From/Contact/Subject 头
    ▼
SIP 消息携带正确的租户身份发送
```

### 新增组件

#### 1. TenantSipConfig（领域模型）

```java
public class TenantSipConfig {
    private String tenantId;       // 对应 JXT 租户
    private String sipId;          // 该租户的 20 位 GB28181 服务器编码
    private String sipDomain;      // SIP 域名（如 "3402000001"）
    private String sipPassword;    // SIP 摘要认证密码
    private String sdpIp;          // SDP 协商 IP（VPN 场景下可能不同）
    private String streamIp;       // 流媒体 URL 的 IP
}
```

#### 2. TenantSipConfigService（应用服务）

```java
public interface TenantSipConfigService {
    TenantSipConfig getConfig(String tenantId);
    TenantSipConfig getConfigByDomain(String sipDomain);
    void reloadFromETCD();  // ETCD Watch 事件触发时调用
}
```

- WVP 启动时通过 jxt-core ETCD Provider 从 ETCD 加载
- 内存缓存（ConcurrentHashMap），ETCD Watch 事件触发刷新
- 每次 SIP 请求零数据库查询

#### 3. TenantSipConfigETCDSubscriber（基础设施层）

- 监听 ETCD 路径 `/jxt/tenants/{tenantCode}` 的租户配置变更
- 从租户配置中解析 SIP 相关配置
- 配置变更时调用 `TenantSipConfigService.reloadFromETCD()`

### 需要修改的组件

#### SipConfig.java — 保留为默认/回退配置

保留现有 `SipConfig` 作为默认 SIP 身份（用于不匹配任何租户域名的设备）。在需要动态身份的地方注入 `TenantSipConfigService` 依赖。

#### SIPRequestHeaderProvider.java

每个构建 SIP 头的方法都需要租户感知的身份：

```java
// 修改前：
SipURI fromSipURI = sipFactory.createSipURI(sipConfig.getId(), sipConfig.getDomain());

// 修改后：
TenantSipConfig tenantCfg = tenantSipConfigService.getConfig(device.getTenantId());
String sipId = tenantCfg != null ? tenantCfg.getSipId() : sipConfig.getId();
String sipDomain = tenantCfg != null ? tenantCfg.getSipDomain() : sipConfig.getDomain();
SipURI fromSipURI = sipFactory.createSipURI(sipId, sipDomain);
```

此模式在该文件中重复约 20 次。可通过辅助方法减少重复：

```java
private SipURI createFromURI(Device device) {
    TenantSipConfig cfg = resolveConfig(device);
    return sipFactory.createSipURI(cfg.getSipId(), cfg.getSipDomain());
}
```

#### RegisterRequestProcessor.java

注册时必须确定租户：

```java
// 从 Request-URI 或 To 头提取 domain
SipURI requestURI = (SipURI) request.getRequestURI();
String domain = requestURI.getHost();
TenantSipConfig tenantCfg = tenantSipConfigService.getConfigByDomain(domain);
if (tenantCfg == null) {
    // 回退到全局配置
}
// 使用 tenantCfg.getSipDomain() 作为摘要认证的 realm
// 使用 tenantCfg.getSipPassword() 进行认证验证
// 保存前设置 device.setTenantId(tenantCfg.getTenantId())
```

#### Device.java + wvp_device 表

添加 tenant_id：

```sql
ALTER TABLE wvp_device ADD COLUMN tenant_id VARCHAR(64) DEFAULT NULL;
CREATE INDEX idx_device_tenant ON wvp_device(tenant_id);
```

#### Redis 键命名空间

所有当前全局的 Redis 键必须按租户命名空间隔离：

```
修改前：wvp:device:{deviceId}
修改后：wvp:{tenantId}:device:{deviceId}

修改前：wvp:ssrc:{mediaServerId}
修改后：wvp:{tenantId}:ssrc:{mediaServerId}
```

#### ZLM Hook 处理

无需结构性改动。`on_stream_changed` Hook 已通过解析 `stream` 获取 `deviceId`。有了 Device 记录上的 `tenant_id`，可以通过以下方式间接解析租户：

```
stream = "{deviceId}_{channelId}" → 解析 deviceId → 查找设备 → 获取 tenantId
```

#### SSRC 管理

SSRC 分配必须按租户隔离，避免跨租户冲突。SSRC 工厂键变更：

```java
// 修改前：
String key = "ssrc_" + mediaServerId;

// 修改后：
String key = "ssrc_" + tenantId + "_" + mediaServerId;
```

### 不需要改动的部分

- **SipLayer.java** — 单个 SipStack，单个端口。无多栈复杂度。
- **ZLM 集成** — 一个 WVP，一个 ZLM。Hook 路由不变。
- **流 ID 格式** — 保持 `{deviceId}_{channelId}`。无需命名空间前缀，租户通过 Device 记录解析。
- **前端** — 无需修改。播放 URL 保持不变。
- **Docker 部署** — 相同的容器拓扑。只需一个 WVP + 一个 ZLM。

### ETCD 配置结构

租户 SIP 配置存储在 ETCD 中已有的租户配置下：

```yaml
# ETCD 路径：/jxt/tenants/{tenantCode}
sip:
  id: "34020000012000000001"          # 该租户的 GB28181 服务器编码
  domain: "3402000001"                # SIP 域名
  password: "admin123"                # SIP 认证密码
  sdpIp: "192.168.1.100"             # 可选，覆盖默认值
  streamIp: "192.168.1.100"          # 可选，覆盖默认值
```

这与 tenant-service 中已有的 `TenantDomainConfig` 结构自然契合。

## 实施计划

### 阶段 1：基础建设（不改变 SIP 行为）

1. 在 `wvp_device` 表添加 `tenant_id` 列
2. 在 `Device.java` 添加 `tenantId` 字段
3. 创建 `TenantSipConfig` 模型
4. 创建 `TenantSipConfigService` 接口
5. 创建 `TenantSipConfigETCDSubscriber`（从 ETCD 加载配置）
6. 在 `SIPRequestHeaderProvider` 中添加辅助方法 `resolveConfig(Device)`

### 阶段 2：注册时租户识别

7. 修改 `RegisterRequestProcessor`，从 Request-URI 提取 domain
8. 通过 `TenantSipConfigService` 按 domain 查找租户
9. 使用租户特定的 realm 和 password 进行摘要认证
10. 注册过程中保存 `device.setTenantId()`

### 阶段 3：出站消息动态 SIP 身份

11. 修改 `SIPRequestHeaderProvider` — 所有 `createXxxRequest` 方法使用 `resolveConfig(device)`
12. 修改 `SIPRequestHeaderPlarformProvider` 处理级联场景
13. 修改 `SIPCommander` 广播 XML（SourceID）
14. 修改 `DigestServerAuthenticationHelper` 支持动态 realm

### 阶段 4：Redis 命名空间和 SSRC 隔离

15. 为设备缓存键添加租户前缀
16. 为 SSRC 工厂键添加租户前缀
17. 为 INVITE 会话键添加租户前缀

### 阶段 5：测试验证

18. 两个租户使用不同的 SIP 编码和域名
19. 各租户设备注册
20. 各租户实时播放、回放、PTZ 控制
21. 验证 SIP From/Contact 头包含正确的租户身份
22. 验证跨租户隔离（租户 A 的设备不能被租户 B 播放）

## 待确认问题

1. **ETCD 配置路径** — SIP 配置应放在租户配置的哪个位置？需要与 tenant-service 团队确认。
2. **tenant-service 集成** — tenant-service 是否已有 SIP 配置字段？是否需要新增？
3. **Nginx 路由** — 当前 Nginx 代理到一个 WVP。单 WVP 场景下没问题，但流 URL 的访问控制需要租户感知。
4. **流 URL 访问控制** — 浏览器打开 `ws://IP:8080/rtp/{streamId}.live.flv` 时，Nginx/WVP 如何确定观看者属于哪个租户？需要基于会话的访问控制。

## 验收标准

- [ ] 两个租户使用不同 SIP 编码可以同时注册设备
- [ ] 设备在所有 SIP From/Contact 头中看到其租户的 SIP 编码
- [ ] SSRC 分配按租户隔离（无跨租户冲突）
- [ ] 新增租户只需修改 ETCD 配置，无需重启 WVP
- [ ] 流访问按租户隔离（租户 A 不能播放租户 B 的摄像头）
- [ ] 现有单租户行为作为默认租户保留

## 依赖项

- tenant-service 必须暴露 SIP 配置字段
- jxt-core ETCD Provider 必须集成到 WVP
- WVP fork 分支策略需考虑上游同步

## 下一步行动

在 tenant-service 中为租户配置添加 SIP 相关字段（sip.id, sip.domain, sip.password），通过 ETCD 分发。这是整个改造的起点，不涉及 WVP 代码改动，但为后续改造奠定配置基础。
