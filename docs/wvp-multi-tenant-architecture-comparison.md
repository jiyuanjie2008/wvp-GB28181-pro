# WVP 多租户方案对比分析

## 1. 背景

将 WVP-GB28181-Pro 作为多租户视频平台使用，每个租户（组织/客户）拥有自己的摄像头设备、流媒体和业务数据。本文档对比三种不同的多租户架构方案，给出推荐。

---

## 2. 三种方案概述

### 方案 1：每租户独立部署（WVP+ZLM+MySQL），Redis 用 DB 隔离

```
租户 A: WVP-A + ZLM-A + MySQL-A     ──┐
租户 B: WVP-B + ZLM-B + MySQL-B     ──┤── Redis (database 6, 7, 8...)
租户 C: WVP-C + ZLM-C + MySQL-C     ──┘
```

每个租户拥有完全独立的 WVP、ZLM、MySQL 容器。所有租户共用一个 Redis 实例，通过 `database` 编号隔离（详见 [wvp-tenant-isolation-via-redis-database.md](wvp-tenant-isolation-via-redis-database.md)）。

### 方案 2：共享 MySQL 和 Redis，每租户独立 WVP+ZLM，用 serverId 隔离

```
租户 A: WVP-A + ZLM-A  ──┐
租户 B: WVP-B + ZLM-B  ──┤── MySQL (server_id 列) + Redis (serverId Key 前缀)
租户 C: WVP-C + ZLM-C  ──┘
```

每个租户有独立的 WVP 和 ZLM，但共用一个 MySQL 和一个 Redis。通过 `serverId` 在数据库和 Redis 中区分不同租户的数据。

### 方案 3：完全共享，WVP 支持多 SIP 身份

```
所有租户 ──→ WVP (多 SIP ID) + ZLM + MySQL + Redis
             (数据库和 Redis 加 tenant_id)
```

单个 WVP 实例服务所有租户，通过支持多个 SIP Server ID 来识别不同租户的设备。数据库和 Redis 通过 `tenant_id` 隔离数据。

---

## 3. 详细对比

### 3.1 方案 1 评估

| 维度 | 评估 |
|------|------|
| **代码改动** | 零 |
| **隔离强度** | 完全隔离（进程级、网络级、数据级） |
| **故障隔离** | 一个租户挂掉不影响其他租户 |
| **资源开销** | 高（每租户 4 个容器：WVP+ZLM+MySQL+Redis 共享） |
| **运维复杂度** | 中（每租户一套，但每套独立简单） |
| **扩容** | 按租户独立扩容 |
| **升级风险** | 低（可以逐租户灰度升级） |

### 3.2 方案 2 评估

| 维度 | 评估 |
|------|------|
| **代码改动** | 大（Redis 侧约 10-15 个文件 + MySQL 侧表结构和 Mapper） |
| **隔离强度** | 逻辑隔离（依赖代码正确性） |
| **故障隔离** | WVP 进程隔离，但 MySQL/Redis 共享有风险 |
| **资源开销** | 中（省了多个 MySQL 容器） |
| **运维复杂度** | 高（共享存储的容量规划、性能调优复杂） |
| **隐藏风险** | 数据库并非所有表都有 `server_id` 列 |

#### 方案 2 需要改动的代码

**Redis 侧：**

- 全局 Key 加 serverId：`VMP_DEVICE_INFO`、`VMP_MEDIA_STREAM_AUTHORITY`、`PUSH_STREAM_LIST` 等
- Pub/Sub Listener 加 serverId 过滤：`RedisCloseStreamMsgListener`、`RedisPushStreamStatusMsgListener`、`RedisPushStreamListMsgListener`、`RedisGroupChangeListener`、`RedisAlarmMsgListener` 等
- Pub/Sub Channel 名加 serverId
- 涉及文件约 10-15 个（详见 [wvp-multi-instance-redis-sharing-analysis.md](wvp-multi-instance-redis-sharing-analysis.md)）

**MySQL 侧：**

- `device` 表缺少 `server_id` 列，需要添加
- `gb_stream` 表缺少 `server_id` 列，需要添加
- `stream_proxy` 表缺少 `server_id` 列，需要添加
- 其他缺少 `server_id` 的表需要逐一排查
- 缺少 `server_id` 的表对应的 Mapper 查询需要全部加 `WHERE server_id = ?` 条件
- 数据库迁移脚本

### 3.3 方案 3 评估

| 维度 | 评估 |
|------|------|
| **代码改动** | 巨大（几乎重构核心架构） |
| **隔离强度** | 逻辑隔离（完全依赖代码） |
| **故障隔离** | 无（单点故障，一挂全挂） |
| **资源开销** | 最低 |
| **运维复杂度** | 中 |
| **开发周期** | 长 |

#### 方案 3 需要改动的内容

- **SIP 栈重构**：JAIN-SIP 需要支持多个 SIP Identity（一个进程监听多个 SIP 域），这是根本性的架构变更
- **数据库改造**：所有表加 `tenant_id` 列，所有 SQL 加 `WHERE tenant_id = ?`
- **Redis 改造**：所有 Key 加租户前缀
- **ZLM 资源隔离**：SSRC 池、流命名空间、端口分配按租户隔离
- **租户上下文传递**：每个 SIP 请求进来时识别属于哪个租户，贯穿整个调用链
- **Session 管理**：设备管理、流管理全部需要租户上下文

---

## 4. 综合对比矩阵

| 维度 | 方案 1（独立部署） | 方案 2（共享存储） | 方案 3（完全共享） |
|------|:-:|:-:|:-:|
| 代码改动 | **零** | **大**（15+ 文件） | **巨大**（架构重构） |
| 隔离强度 | **完全** | 逻辑 | 逻辑 |
| 故障隔离 | **强** | 中 | **弱（单点故障）** |
| 资源开销 | 高 | 中 | **低** |
| 开发周期 | **即部署即用** | 1-2 周 | 1-2 月 |
| 升级灵活性 | **逐租户灰度** | 统一升级 | 统一升级 |
| 单租户性能 | **独享** | 共享竞争 | 共享竞争 |
| 扩展上限 | 受服务器资源限制 | 受 MySQL/Redis 容量限制 | 受单进程性能限制 |
| 运维复杂度 | 低 | 高 | 中 |
| 排障难度 | **低（独立环境）** | 中（需排查是否跨租户影响） | 高（需定位租户上下文） |

---

## 5. 推荐方案：方案 1

### 推荐理由

1. **零代码改动** — 不需要改 WVP 任何一行代码，今天就能部署
2. **完全隔离** — 一个租户的设备数据、流媒体、数据库完全不会被其他租户影响
3. **故障隔离** — 某个租户的 WVP 或 MySQL 出问题，其他租户不受影响
4. **运维简单** — 每套都是标准部署，排障时不用考虑跨租户干扰
5. **灵活升级** — 可以先给一个租户升级 WVP 版本验证，其他不动

### 关于资源开销

方案 2 和方案 3 看似省资源，但实际上：

- 省的只是 MySQL 容器的资源（每个 MySQL 空库占内存很小，约 200-400MB）
- 换来的是大量的代码改动和测试工作
- 以及共享存储带来的容量规划、性能竞争、故障传播等运维负担

### 大规模场景

当租户数量非常多（50+）时，应考虑 Kubernetes 编排 + Helm Chart 模板化部署，方案 1 反而更适合容器化自动管理：

```bash
# 通过 Helm 一键部署新租户
helm install tenant-a ./wvp-chart \
  --set sip.id=34020000002000000001 \
  --set sip.port=5060 \
  --set redis.database=6 \
  --set mysql.database=wvp_tenant_a
```

### 方案 2 的适用场景

仅在以下条件同时满足时考虑方案 2：

- 租户数量中等（10-30 个）
- 服务器资源确实紧张
- 有能力投入开发资源改造 WVP 代码
- 有完善的自动化测试覆盖改动范围

### 不推荐方案 3 的原因

方案 3 本质上是将 WVP 改造成多租户 SaaS 平台，涉及 SIP 栈、数据库、Redis、流媒体全链路改造，投入产出比不合理。如果真有这个需求，建议在 WVP 前面加一层网关做租户路由，后端仍然用方案 1 的独立实例。

---

## 6. 相关文档

- [WVP 多实例共享 Redis 分析报告](wvp-multi-instance-redis-sharing-analysis.md) — Redis Key 隔离分析、Pub/Sub 冲突分析
- [WVP 多租户 Redis Database 隔离方案](wvp-tenant-isolation-via-redis-database.md) — 方案 1 中 Redis 隔离的详细配置
