# WVP 多租户隔离方案：Redis Database 隔离

## 1. 方案概述

将每个 WVP 实例视为一个独立租户，通过 Redis 的 `database` 编号实现租户间的数据隔离。

**核心结论：WVP 代码无需任何修改，仅需调整配置。**

---

## 2. 方案原理

Redis 实例默认提供 16 个逻辑 database（编号 0-15），不同 database 之间的 Key 空间、Pub/Sub 通道完全隔离。

```
Redis 容器实例 (端口 6379)
├── database 0  (保留)
├── database 1  (保留)
├── ...
├── database 6  → WVP 租户 A
├── database 7  → WVP 租户 B
├── database 8  → WVP 租户 C
├── ...
└── database 15 (保留)
```

### 为什么 database 隔离有效

Redis 的 `SELECT` 命令在连接建立时切换 database，之后该连接上的所有操作（Key 读写、Pub/Sub 订阅）都限定在该 database 内。Spring Data Redis 在创建连接池时自动执行 `SELECT`，WVP 代码完全不感知。

这意味着：

| 隔离维度 | 是否隔离 | 说明 |
|----------|---------|------|
| Key 读写 | 完全隔离 | 不同 database 的 Key 互不可见 |
| Pub/Sub 通道 | 完全隔离 | 订阅和发布限定在各自 database 内 |
| Hash/Set/List/ZSet | 完全隔离 | 数据结构操作限定在各自 database 内 |
| 原子计数器 (INCR) | 完全隔离 | 各自独立计数 |
| SCAN 命令 | 完全隔离 | 只扫描当前 database 的 Key |
| Spring Cache | 完全隔离 | 缓存数据存入各自 database |

---

## 3. 为什么不能共用同一个 database

如果多个独立 WVP（不同租户）共用同一个 Redis database，会出现以下冲突：

### 3.1 全局 Key 冲突

以下 Key 不包含 `serverId`，多个 WVP 写入同一个 Key 会互相覆盖：

| Key | 类型 | 冲突后果 |
|-----|------|---------|
| `VMP_DEVICE_INFO` | HASH | 设备缓存互相覆盖，`removeAllDevice()` 会清掉所有租户的设备 |
| `VMP_MEDIA_STREAM_AUTHORITY` | HASH | 流授权信息混淆 |
| `VMP_PUSH_STREAM_LIST_{app}_{stream}` | STRING | 推流列表互相覆盖 |
| `VMP_CATALOG_DATA` | HASH | 目录同步数据混淆 |
| `SYSTEM_ACCESS_TOKEN` | STRING | 管理员 Token 互相覆盖 |
| `VMP_FTP_USER_{username}` | STRING | FTP 用户会话冲突 |

### 3.2 Pub/Sub 消息广播冲突

以下 Listener 不按 `serverId` 过滤，所有实例都会处理每条消息：

| Listener | 冲突后果 |
|----------|---------|
| `RedisCloseStreamMsgListener` | 所有 WVP 都尝试关闭同一个流 |
| `RedisPushStreamStatusMsgListener` | 所有 WVP 都执行"全部下线"操作 |
| `RedisPushStreamListMsgListener` | 所有 WVP 同时写入相同的数据库记录 |
| `RedisGroupChangeListener` | 所有 WVP 同时执行分组变更操作 |
| `RedisAlarmMsgListener` | 所有 WVP 都处理报警消息 |

### 3.3 数据库重复操作

一个 WVP 发出控制消息后，所有共用 database 的 WVP 都会收到并执行相同的数据库操作，导致数据异常。

---

## 4. 部署配置

### 4.1 每个租户的配置差异

每个租户的 WVP 实例需要配置以下不同的参数：

```yaml
# application-{profile}.yml

spring:
  data:
    redis:
      host: redis-host        # 相同：指向同一个 Redis 实例
      port: 6379              # 相同：同一个端口
      database: 6             # 不同：每个租户使用不同的 database 编号
      password: your-password # 相同（如果需要）

# GB28181 SIP 配置
sip:
  id: 34020000002000000001    # 不同：每个租户的 SIP 服务器 ID
  domain: 3402000000          # 不同：每个租户的 SIP 域
  port: 5060                  # 不同：每个租户使用不同的 SIP 端口

# WVP 实例标识
user-settings:
  server-id: wvp-tenant-a     # 不同：实例标识（仅用于标识，database 已提供隔离）
```

### 4.2 完整配置示例

**租户 A（database 6）：**

```yaml
spring:
  data:
    redis:
      host: polaris-redis
      port: 6379
      database: 6

sip:
  id: 34020000002000000001
  domain: 3402000000
  port: 5060

media:
  id: 34020000002000000001
  http-port: 80
  http-host: polaris-media-a

user-settings:
  server-id: wvp-tenant-a
```

**租户 B（database 7）：**

```yaml
spring:
  data:
    redis:
      host: polaris-redis
      port: 6379
      database: 7

sip:
  id: 34020000002000000002
  domain: 3402000001
  port: 5061

media:
  id: 34020000002000000002
  http-port: 81
  http-host: polaris-media-b

user-settings:
  server-id: wvp-tenant-b
```

### 4.3 Docker Compose 示例

```yaml
services:
  # 共享 Redis
  polaris-redis:
    image: redis:latest
    ports:
      - "6379:6379"

  # 租户 A
  polaris-wvp-a:
    image: wvp-pro:latest
    environment:
      SPRING_DATA_REDIS_HOST: polaris-redis
      SPRING_DATA_REDIS_PORT: 6379
      SPRING_DATA_REDIS_DATABASE: 6
      SIP_ID: "34020000002000000001"
      SIP_PORT: 5060

  # 租户 B
  polaris-wvp-b:
    image: wvp-pro:latest
    environment:
      SPRING_DATA_REDIS_HOST: polaris-redis
      SPRING_DATA_REDIS_PORT: 6379
      SPRING_DATA_REDIS_DATABASE: 7
      SIP_ID: "34020000002000000002"
      SIP_PORT: 5061
```

---

## 5. 每个租户必须独立配置的组件

| 组件 | 是否需要独立 | 原因 |
|------|------------|------|
| **Redis** | 共享（通过 database 隔离） | 本方案核心 |
| **ZLM 媒体服务器** | 必须独立 | 流媒体端口、流 ID 命名空间不能冲突 |
| **MySQL 数据库** | 必须独立（或不同 schema） | 设备、通道、录像等业务数据完全隔离 |
| **SIP 端口** | 必须独立 | 每个租户监听不同端口接收设备注册 |
| **HTTP 端口** | 必须独立 | WVP Web API 端口不能冲突 |

---

## 6. 容量与限制

### 6.1 Redis Database 数量

- 默认 16 个 database（编号 0-15）
- 可通过 Redis 配置 `databases` 参数扩展（如设为 64 或 128）
- 修改 `redis.conf` 中的 `databases 128` 后重启 Redis 生效

### 6.2 资源共享

| 资源 | 说明 |
|------|------|
| 内存 | 所有 database 共享 Redis 实例的总内存，一个租户的大量数据会影响其他租户 |
| CPU | 共享，但 Redis 单线程模型下通常不是瓶颈 |
| 连接池 | 每个租户独立连接池，互不影响 |
| 持久化 | RDB/AOF 备份包含所有 database 的数据 |

### 6.3 注意事项

- `FLUSHDB` 只清除当前 database，不会影响其他租户
- `FLUSHALL` 会清除所有 database，**生产环境应禁用**
- `INFO keyspace` 可以查看每个 database 的 Key 数量，用于监控各租户的 Redis 使用量
- Redis Cluster 模式下仅支持 database 0，本方案不适用于 Cluster 部署

---

## 7. 监控与运维

### 7.1 查看各租户的 Key 数量

```bash
redis-cli INFO keyspace
```

输出示例：

```
db6:keys=523,expires=89,avg_ttl=3600000    # 租户 A
db7:keys=312,expires=45,avg_ttl=1800000    # 租户 B
```

### 7.2 查看特定租户的 Key

```bash
redis-cli -n 6 DBSIZE    # 租户 A 的 Key 总数
redis-cli -n 7 DBSIZE    # 租户 B 的 Key 总数
```

### 7.3 清除特定租户的 Redis 数据

```bash
redis-cli -n 6 FLUSHDB   # 仅清除租户 A，不影响租户 B
```

---

## 8. 方案对比

| 方案 | 代码改动 | 隔离程度 | 资源开销 | 运维复杂度 |
|------|---------|---------|---------|-----------|
| **Redis Database 隔离（本方案）** | 零 | 完全 | 低（共享实例） | 低 |
| 每个 WVP 独占 Redis 容器 | 零 | 完全 | 中（多容器） | 中 |
| 共用 database + serverId 前缀 | 大（10-15 个文件） | 需代码审查 | 低 | 高 |
| Redis Cluster | 零（需改配置） | 完全 | 高 | 高 |

---

## 9. 总结

使用 Redis Database 隔离是多租户 WVP 部署的**最简方案**：

- WVP 代码**零修改**
- 仅需每个租户配置不同的 `spring.data.redis.database` 编号
- Key、Pub/Sub、原子操作、Spring Cache 全部自动隔离
- 一个 Redis 容器服务多个租户，资源利用率高
- 可通过 `redis.conf` 的 `databases` 参数扩展租户数量上限
