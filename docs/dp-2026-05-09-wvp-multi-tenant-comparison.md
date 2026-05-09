# WVP 多租户方案对比分析

> 文档编号: dp-2026-05-09-wvp-multi-tenant-comparison
> 日期: 2026-05-09
> 状态: Draft
> 作者: jiyuanjie

---

## 1. 四种方案定义

### 方案 1：全独立部署

```
┌─────────┐ ┌─────────┐ ┌─────────┐
│  WVP A  │ │  WVP B  │ │  WVP C  │  ← 独立实例
└────┬────┘ └────┬────┘ └────┬────┘
┌────┴────┐ ┌────┴────┐ ┌────┴────┐
│  ZLM A  │ │  ZLM B  │ │  ZLM C  │  ← 独立实例
└────┬────┘ └────┬────┘ └────┬────┘
┌────┴────┐ ┌────┴────┐ ┌────┴────┐
│ MySQL A │ │ MySQL B │ │ MySQL C │  ← 独立实例
└────┬────┘ └────┬────┘ └────┬────┘
     │          │          │
     ▼          ▼          ▼
┌──────────────────────────────────┐
│         Redis (共享)             │
│  DB 0   │  DB 1   │  DB 2       │  ← DB 隔离
└──────────────────────────────────┘
```

- WVP：1 实例/租户 ✓ 无代码改动
- ZLM：1 实例/租户 ✓ 无代码改动
- MySQL：1 实例/租户 ✓ 无代码改动
- Redis：1 实例共 16 DB，按 DB index 隔离 ✓ 无代码改动

### 方案 2：半共享 + Redis DB 隔离

```
┌─────────┐ ┌─────────┐ ┌─────────┐
│  WVP A  │ │  WVP B  │ │  WVP C  │  ← 独立实例
└────┬────┘ └────┬────┘ └────┬────┘
┌────┴────┐ ┌────┴────┐ ┌────┴────┐
│  ZLM A  │ │  ZLM B  │ │  ZLM C  │  ← 独立实例
└────┬────┘ └────┬────┘ └────┬────┘
     │          │          │
     ▼          ▼          ▼
┌──────────────────────────────────┐
│          MySQL (共享)            │
│   server_id 列区分租户数据        │  ← 共库
└──────────────────────────────────┘
     │          │          │
     ▼          ▼          ▼
┌──────────────────────────────────┐
│         Redis (共享)             │
│  DB 0   │  DB 1   │  DB 2       │  ← DB 隔离
└──────────────────────────────────┘
```

- WVP：1 实例/租户 ✓ 无代码改动
- ZLM：1 实例/租户 ✓ 无代码改动
- MySQL：1 实例共享，`server_id` 列区分 ✓ 无代码改动
- Redis：1 实例共 16 DB，按 DB index 隔离 ✓ 无代码改动

### 方案 3：全共享 + 代码改造

```
┌──────────────────────────────────┐
│         WVP (共享,多 sip 支持)    │
│  需改造: 多 sip.id、tenant_id 维度 │  ← 大量代码改动
└────────────────┬─────────────────┘
                 │
┌────────────────┴─────────────────┐
│           ZLM (共享)              │  ← 视频流无隔离
└────────────────┬─────────────────┘
                 │
┌────────────────┴─────────────────┐
│        MySQL (共享+tenant_id)     │  ← 加列改造
└────────────────┬─────────────────┘
                 │
┌────────────────┴─────────────────┐
│       Redis (共享+tenant_id)      │  ← 加维度改造
└──────────────────────────────────┘
```

- WVP：1 实例共享 → 需支持多 `sip.id`、请求级租户上下文
- ZLM：1 实例共享 → 视频流互相影响
- MySQL：1 实例共享，加 `tenant_id` 列 → 需大量改造
- Redis：1 实例共享，pub/sub 频道加租户前缀 → 需大量改造
- 估计改动量：3000~5000 行，30+ 文件

### 方案 4：共享 WVP，独占 ZLM + MySQL

```
┌──────────────────────────────────┐
│       WVP (共享,深度改造)          │
│  需支持:多 sip.id/多数据库/        │
│  多 ZLM/多 Redis DB 动态路由       │  ← 最大代码改动
└────────┬─────────────────────────┘
         │
 ┌───────┼───────┐
 │       │       │
┌┴───┐ ┌┴───┐ ┌┴───┐
│ZLM A│ │ZLM B│ │ZLM C│  ← 独占 ✓
└─┬──┘ └─┬──┘ └─┬──┘
┌─┴──┐ ┌─┴──┐ ┌─┴──┐
│MySQL│ │MySQL│ │MySQL│  ← 独占 ✓
│  A  │ │  B  │ │  C  │
└─┬──┘ └─┬──┘ └─┬──┘
  │      │      │
  ▼      ▼      ▼
┌──────────────────────────────────┐
│         Redis (共享)             │
│  DB 0   │  DB 1   │  DB 2       │  ← DB 隔离
└──────────────────────────────────┘
```

- WVP：1 实例共享 → 需支持动态数据源路由、多 Redis DB 切换、多 ZLM 连接
- ZLM：1 实例/租户 ✓
- MySQL：1 实例/租户 ✓
- Redis：1 实例共 16 DB，按 DB index 隔离 ✓
- 估计改动量：4000~6000 行，30+ 文件（比方案 3 更大）

---

## 2. 核心维度对比

### 2.1 代码改动量

| | 方案 1 | 方案 2 | 方案 3 | 方案 4 |
|---|---|---|---|---|
| WVP 改动 | **无** | **无** | 大量 | 更大 |
| ZLM 改动 | 无 | 无 | 无 | 无 |
| MySQL 改动 | 无 | 无 | 加 tenant_id 列 | 无 |
| Redis 改动 | 无 | 无 | 加 tenant_id 维度 | 无 |
| 改动行数 | **0** | **0** | 3000~5000 | 4000~6000 |
| 改动文件 | **0** | **0** | 30+ | 30+ |
| 上游合并风险 | **无** | **无** | 持续存在 | 持续存在 |

### 2.2 数据隔离强度

| | 方案 1 | 方案 2 | 方案 3 | 方案 4 |
|---|---|---|---|---|
| 视频流 (ZLM) | **硬隔离** ✓ | **硬隔离** ✓ | 无隔离 ✗ | **硬隔离** ✓ |
| 数据库 (MySQL) | **实例级硬隔离** ✓ | SQL 条件软隔离 △ | SQL 条件软隔离 △ | **实例级硬隔离** ✓ |
| 缓存 (Redis key) | DB 隔离 ✓ | DB 隔离 ✓ | DB 隔离 ✓ | DB 隔离 ✓ |
| 消息 (pub/sub) | DB 隔离 ✓ | DB 隔离 ✓ | 改造后隔离 △ | DB 隔离 ✓ |
| 跨租户数据泄露风险 | **零** | 极小（依赖 `server_id` 过滤） | 极小（依赖 `tenant_id` 过滤） | **零** |

### 2.3 故障隔离

| | 方案 1 | 方案 2 | 方案 3 | 方案 4 |
|---|---|---|---|---|
| 单租户 WVP 故障 | 不影响其他 ✓ | 不影响其他 ✓ | **全局影响** ✗ | **全局影响** ✗ |
| 单租户 ZLM 故障 | 不影响其他 ✓ | 不影响其他 ✓ | **全局影响** ✗ | 不影响其他 ✓ |
| 单租户 MySQL 故障 | 不影响其他 ✓ | **全局影响** ✗ | **全局影响** ✗ | 不影响其他 ✓ |
| Redis 故障 | **全局影响** ✗ | **全局影响** ✗ | **全局影响** ✗ | **全局影响** ✗ |
| 单租户流量攻击 ZLM | 不影响其他 ✓ | 不影响其他 ✓ | **全局影响** ✗ | 不影响其他 ✓ |

### 2.4 运维复杂度

| | 方案 1 | 方案 2 | 方案 3 | 方案 4 |
|---|---|---|---|---|
| 备份脚本 | N 套（每租户一套） | 1 套（全量，恢复要过滤） | 1 套 | N 套 |
| 单租户数据恢复 | 精确恢复 ✓ | 需按 server_id 条件恢复 △ | 需按 tenant_id 条件恢复 △ | 精确恢复 ✓ |
| 灰度升级 | 可逐个租户 ✓ | 可逐个租户 ✓ | 一停全停 ✗ | 一停全停 ✗ |
| 新增租户 | 新增一套全栈配置 | 新增 WVP+ZLM，改 MySQL & Redis 配置 | 改配置文件（简单） | 新增 ZLM+MySQL，改 WVP 配置 |
| 资源管理 | N 套配置，脚本化 | N-1 套配置 + 1 共享 MySQL | **1 套配置** ✓ | N-1 套配置 + 1 共享 WVP |

### 2.5 资源消耗

以 3 个租户为例：

| | 方案 1 | 方案 2 | 方案 3 | 方案 4 |
|---|---|---|---|---|
| JVM 进程 | 3 | 3 | **1**（需扩容） | **1**（需扩容） |
| ZLM 进程 | 3 | 3 | **1** | 3 |
| MySQL 实例 | 3 | **1** | **1** | 3 |
| Redis 实例 | 1 | 1 | 1 | 1 |
| CPU 估算 | 18 cores | 18 cores | 12 cores | 12 cores |
| 内存估算 | 18 GB | 18 GB | 12 GB | 12 GB |

### 2.6 租户数上限

| | 方案 1 | 方案 2 | 方案 3 | 方案 4 |
|---|---|---|---|---|
| 硬上限 | Redis DB x 16 | Redis DB x 16 | 无硬上限 | Redis DB x 16 |
| 扩容方式 | 加 Redis 实例 | 加 Redis 实例 | 不需要 | 加 Redis 实例 |

### 2.7 综合评级

| 维度 | 方案 1 | 方案 2 | 方案 3 | 方案 4 |
|---|---|---|---|---|
| 代码改动 | ★★★★★ | ★★★★★ | ★☆☆☆☆ | ★☆☆☆☆ |
| 数据隔离 | ★★★★★ | ★★★★☆ | ★★☆☆☆ | ★★★★★ |
| 故障隔离 | ★★★★☆ | ★★★☆☆ | ★☆☆☆☆ | ★★★☆☆ |
| 运维简单 | ★★★☆☆ | ★★★★☆ | ★★★★★ | ★★☆☆☆ |
| 资源效率 | ★★★☆☆ | ★★★★☆ | ★★★★★ | ★★★☆☆ |
| 可扩展性 | ★★★★☆ | ★★★★☆ | ★★★★★ | ★★★☆☆ |

---

## 3. 关键分析：每种资源"共享 vs 独占"的真正成本

四种资源的共享代价不同，不能一概而论：

| 资源 | 独立成本 | 共享代价 | 结论 |
|------|----------|----------|------|
| **WVP** | 极低（2GB 堆，无状态） | 极高（需深度改造，多数据源路由等） | **应该独立** |
| **ZLM** | 高（视频流转码/转发） | 极高（流量互相影响，安全性最差） | **应该独立** |
| **MySQL** | 中（多一套运维） | 中（软隔离，恢复麻烦） | **尽量独立** |
| **Redis** | 低（DB 隔离天然解决） | 几乎为零 | **可以共享** |

**核心结论**：方案 1（全独立）和方案 2（仅共享 MySQL）的差距微乎其微——只差一个 MySQL 实例的运维成本。方案 3 和 4 试图共享那些"独立成本低、共享代价高"的资源（特别是 WVP），代价收益比严重倒挂。

---

## 4. Redis 高可用方案选择

### 4.1 Redis Sentinel vs Cluster

| | Sentinel | Cluster |
|---|---|---|
| 自动故障转移 | ✓ | ✓ |
| 数据冗余（副本） | ✓ | ✓ |
| 支持多 DB（0~15） | ✓ | **✗（仅 DB 0）** |
| 水平分片 | ✗ | ✓ |
| 适用数据规模 | 几十 GB | TB 级 |
| 脑裂防护 | 法定人数投票 | 法定人数投票 |
| 客户端复杂度 | 简单 | 复杂（槽位感知） |
| **DB 隔离方案兼容** | **直接可用 ✓** | **不兼容 ✗** |

**推荐**：WVP 场景下（单租户 ~200MB，16 租户 ~3.2GB），**Redis Sentinel 是更优选择**——可靠性等价于 Cluster，且支持多 DB。Cluster 的水平分片能力在 WVP 场景下用不上。

### 4.2 Sentinel 部署建议

```
3 Sentinel 节点（法定人数 2）
  +
1 主节点 + 1 从节点
  =
5 节点，各 DB 隔离方案完整可用
```

### 4.3 若运维规范强制要求 Cluster

采用双 Redis 架构：

```
Redis Cluster (DB 0)
  承担：key-value 存储（serverId 前缀隔离）
  
  +
  
Redis Sentinel / 单机 (DB 0~15)
  承担：pub/sub 频道隔离
```

> 注意：此方案需要 WVP 代码层面区分两个 RedisTemplate。

---

## 5. 推荐决策树

```
                    租户数？
                   /        \
              ≤ 16           > 16 或快速增长
              /                  \
        合规要求？             共享 ZLM 能接受？
       /        \             /            \
  强隔离        一般        能接受        不能接受
   │             │           │              │
   ▼             ▼           ▼              ▼
方案 1        方案 2       方案 3         方案 2
(全独立)   (+ Redis DB隔离) (+代码改造)   (+多Redis实例)
```

---

## 6. 方案 1 部署参考

```yaml
# docker-compose.yml

services:
  # ========== 租户 A ==========
  wvp-tenant-a:
    image: wvp:latest
    ports: ["18080:18080", "15060:5060/udp"]
    volumes: [./config/tenant-a:/app/config]
    depends_on: [mysql-tenant-a, redis]

  zlm-tenant-a:
    image: zlmediakit/zlmediakit:master
    ports: ["10080:80", "10554:554", "10000:10000/udp"]
    volumes: [./config/zlm-tenant-a:/app/config]

  mysql-tenant-a:
    image: mysql:8.0
    ports: ["3307:3306"]
    environment:
      MYSQL_ROOT_PASSWORD: tenant_a_pwd
      MYSQL_DATABASE: wvp
    volumes: [mysql-a-data:/var/lib/mysql]

  # ========== 租户 B ==========
  wvp-tenant-b:
    image: wvp:latest
    ports: ["28080:28080", "25060:5060/udp"]
    volumes: [./config/tenant-b:/app/config]
    depends_on: [mysql-tenant-b, redis]

  zlm-tenant-b:
    image: zlmediakit/zlmediakit:master
    ports: ["20080:80", "20554:554", "20000:10000/udp"]
    volumes: [./config/zlm-tenant-b:/app/config]

  mysql-tenant-b:
    image: mysql:8.0
    ports: ["3308:3306"]
    environment:
      MYSQL_ROOT_PASSWORD: tenant_b_pwd
      MYSQL_DATABASE: wvp
    volumes: [mysql-b-data:/var/lib/mysql]

  # ========== 共享 Redis Sentinel ==========
  redis-master:
    image: redis:7
    ports: ["6379:6379"]
    command: redis-server --appendonly yes

  redis-replica:
    image: redis:7
    command: redis-server --replicaof redis-master 6379

  redis-sentinel-1:
    image: redis:7
    ports: ["26379:26379"]
    command: redis-sentinel /etc/redis/sentinel.conf
    volumes: [./config/sentinel-1.conf:/etc/redis/sentinel.conf]

  redis-sentinel-2:
    image: redis:7
    ports: ["26380:26379"]
    command: redis-sentinel /etc/redis/sentinel.conf
    volumes: [./config/sentinel-2.conf:/etc/redis/sentinel.conf]

  redis-sentinel-3:
    image: redis:7
    ports: ["26381:26379"]
    command: redis-sentinel /etc/redis/sentinel.conf
    volumes: [./config/sentinel-3.conf:/etc/redis/sentinel.conf]

volumes:
  mysql-a-data:
  mysql-b-data:
```

### 租户 A 配置

```yaml
# config/tenant-a/application.yml
server:
  port: 18080

spring:
  datasource:
    url: jdbc:mysql://mysql-tenant-a:3306/wvp
    username: wvp
    password: tenant_a_pwd
  data:
    redis:
      host: redis-master
      port: 6379
      database: 0          # ← 租户 A 使用 DB 0
      password: xxx
      timeout: 10000
      sentinel:
        master: mymaster
        nodes: redis-sentinel-1:26379,redis-sentinel-2:26380,redis-sentinel-3:26381

sip:
  ip: 0.0.0.0
  port: 5060
  domain: 4401020001
  id: 44010200012000000001
  password: tenant_a_sip_password

user-settings:
  server-id: tenant-a
```

### 租户 B 配置

```yaml
# config/tenant-b/application.yml
server:
  port: 28080

spring:
  datasource:
    url: jdbc:mysql://mysql-tenant-b:3306/wvp
    username: wvp
    password: tenant_b_pwd
  data:
    redis:
      host: redis-master
      port: 6379
      database: 1          # ← 唯一差异
      password: xxx
      timeout: 10000
      sentinel:
        master: mymaster
        nodes: redis-sentinel-1:26379,redis-sentinel-2:26380,redis-sentinel-3:26381

sip:
  ip: 0.0.0.0
  port: 5060
  domain: 4401020002
  id: 44010200022000000001
  password: tenant_b_sip_password

user-settings:
  server-id: tenant-b
```

---

## 7. 方案选择总结

| 场景 | 推荐方案 | 理由 |
|------|----------|------|
| 租户 ≤ 5，公检法/合规要求强隔离 | **方案 1** | 零代码改动 + 实例级硬隔离 + 合规友好 |
| 租户 5~16，一般企业场景 | **方案 2** | 零代码改动 + 省 MySQL 运维 |
| 租户 > 16 且快速增长 | **方案 2 扩展** | 多 Redis 实例，仍零代码改动 |
| 租户 > 50，SaaS 化对外售卖 | **方案 3** | 全共享 + 代码改造，运维成本最低 |
| 任何场景 | **不建议方案 4** | 共享了最不该共享的 WVP，独立了最能共享的 MySQL，代价收益倒挂 |

---

## 8. 附录

### 8.1 相关文件索引

| 文件 | 角色 |
|------|------|
| `src/main/java/com/genersoft/iot/vmp/conf/UserSetting.java` | serverId 定义 |
| `src/main/java/com/genersoft/iot/vmp/conf/SipConfig.java` | SIP ID / domain 定义 |
| `src/main/java/com/genersoft/iot/vmp/common/VideoManagerConstants.java` | Redis key 和 pub/sub 频道常量 |
| `src/main/java/com/genersoft/iot/vmp/storager/impl/RedisCatchStorageImpl.java` | Redis 存储和 pub/sub 发布 |
| `src/main/java/com/genersoft/iot/vmp/conf/redis/RedisMsgListenConfig.java` | pub/sub 订阅注册 |
| `src/main/java/com/genersoft/iot/vmp/conf/redis/RedisRpcConfig.java` | Redis RPC 配置 |
| `数据库/2.7.4/初始化-mysql-2.7.4.sql` | 数据库表结构（已有 server_id 列） |

### 8.2 Pub/Sub 频道清单 (16 个)

**WVP 自己监听（9 个）**：
`VM_MSG_GPS`, `VM_MSG_PUSH_STREAM_STATUS_CHANGE`, `VM_MSG_PUSH_STREAM_LIST_CHANGE`, `VM_MSG_STREAM_PUSH_CLOSE`, `VM_MSG_STREAM_PUSH_RESPONSE`, `VM_MSG_GROUP_LIST_RESPONSE`, `VM_MSG_GROUP_LIST_CHANGE`, `alarm_receive`, `WVP_REDIS_REQUEST_CHANNEL_KEY`(RPC)

**给外部系统消费（7 个）**：
`VM_MSG_STREAM_PUSH_REQUESTED`, `VM_MSG_STREAM_START_PLAY_NOTIFY`, `VM_MSG_STREAM_STOP_PLAY_NOTIFY`, `VM_MSG_STREAM_PUSH_CLOSE_REQUESTED`, `VM_MSG_SUBSCRIBE_ALARM`, `VM_MSG_SUBSCRIBE_DEVICE_STATUS`, `WVP_MSG_STREAM_CHANGE_*`

> 方案 1/方案 2 + Redis DB 隔离：以上全部天然隔离，无需改动。
