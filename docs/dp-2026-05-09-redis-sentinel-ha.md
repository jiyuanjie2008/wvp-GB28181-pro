# Redis Sentinel 高可用部署方案

> 文档编号: dp-2026-05-09-redis-sentinel-ha
> 日期: 2026-05-09
> 状态: Draft
> 作者: jiyuanjie

---

## 1. 为什么选 Sentinel 而不是 Cluster

| | Sentinel | Cluster |
|---|---|---|
| 自动故障转移 | ✓ | ✓ |
| 数据冗余（副本） | ✓ | ✓ |
| 支持多 DB（0~15） | ✓ | **✗（仅 DB 0）** |
| 水平分片 | ✗（单机数据量够用） | ✓（TB 级） |
| 脑裂防护 | 法定人数（≥2 哨兵） | 法定人数（≥N/2+1） |
| 客户端复杂度 | 简单，连哨兵拿主地址 | 复杂，槽位感知 + 重定向 |
| 运维复杂度 | 低 | 高 |
| **对 WVP 多租户的意义** | **DB 隔离天然可用** | **不兼容，需要绕路** |

核心逻辑：WVP 场景单租户 Redis 内存 ~200MB，16 租户 ~3.2GB，单机完全够用。Cluster 解决的是水平分片问题，WVP 不需要。而 Sentinel 保留了多 DB 支持，直接兼容方案 1/方案 2 的 DB 隔离。

---

## 2. 架构总览

```
                        ┌──────────────┐
                        │   WVP 租户 A  │──→ Redis DB 0
                        └──────────────┘
                        ┌──────────────┐
                        │   WVP 租户 B  │──→ Redis DB 1
                        └──────────────┘
                        ┌──────────────┐
                        │   WVP 租户 C  │──→ Redis DB 2
                        └──────────────┘
                               │
                               │ 客户端从 Sentinel 获取当前 Master 地址
                               ▼
                   ┌───────────────────────┐
                   │    Sentinel 集群       │
                   │  (3 节点, 法定人数 2)  │
                   │                       │
                   │  sentinel-1 :26379    │── 互相监控
                   │  sentinel-2 :26380    │── 互相监控
                   │  sentinel-3 :26381    │── 互相监控
                   └──────────┬────────────┘
                              │ 监控
                   ┌──────────▼────────────┐
                   │    Redis 数据节点      │
                   │                       │
                   │  master    :6379      │── 异步复制
                   │  replica-1 :6380      │
                   └───────────────────────┘

故障时:
  Master 挂掉 → 3 哨兵投票 (2 票通过) → 提升 Replica 为新 Master
  → Sentinel 通知所有客户端新地址 → 客户端自动重连
```

---

## 3. Docker Compose 部署

### 3.1 完整配置

```yaml
# docker-compose-redis-sentinel.yml
version: '3.8'

services:
  # ============================================================
  # 数据节点
  # ============================================================

  redis-master:
    image: redis:7-alpine
    container_name: redis-master
    ports:
      - "6379:6379"
    volumes:
      - redis-master-data:/data
      - ./config/redis/redis.conf:/usr/local/etc/redis/redis.conf
    command: redis-server /usr/local/etc/redis/redis.conf
    restart: unless-stopped
    networks:
      - redis-net

  redis-replica:
    image: redis:7-alpine
    container_name: redis-replica
    ports:
      - "6380:6379"
    volumes:
      - redis-replica-data:/data
      - ./config/redis/redis-replica.conf:/usr/local/etc/redis/redis.conf
    command: redis-server /usr/local/etc/redis/redis.conf
    restart: unless-stopped
    networks:
      - redis-net
    depends_on:
      - redis-master

  # ============================================================
  # Sentinel 节点 (3 个, 法定人数 2)
  # ============================================================

  sentinel-1:
    image: redis:7-alpine
    container_name: redis-sentinel-1
    ports:
      - "26379:26379"
    volumes:
      - ./config/redis/sentinel-1.conf:/usr/local/etc/redis/sentinel.conf
    command: redis-sentinel /usr/local/etc/redis/sentinel.conf
    restart: unless-stopped
    networks:
      - redis-net
    depends_on:
      - redis-master
      - redis-replica

  sentinel-2:
    image: redis:7-alpine
    container_name: redis-sentinel-2
    ports:
      - "26380:26379"
    volumes:
      - ./config/redis/sentinel-2.conf:/usr/local/etc/redis/sentinel.conf
    command: redis-sentinel /usr/local/etc/redis/sentinel.conf
    restart: unless-stopped
    networks:
      - redis-net
    depends_on:
      - redis-master
      - redis-replica

  sentinel-3:
    image: redis:7-alpine
    container_name: redis-sentinel-3
    ports:
      - "26381:26379"
    volumes:
      - ./config/redis/sentinel-3.conf:/usr/local/etc/redis/sentinel.conf
    command: redis-sentinel /usr/local/etc/redis/sentinel.conf
    restart: unless-stopped
    networks:
      - redis-net
    depends_on:
      - redis-master
      - redis-replica

volumes:
  redis-master-data:
  redis-replica-data:

networks:
  redis-net:
    driver: bridge
```

### 3.2 Redis Master 配置

```conf
# config/redis/redis.conf

bind 0.0.0.0
port 6379
protected-mode no

# 持久化
appendonly yes
appendfsync everysec

# 密码 (生产环境必须设置)
requirepass jxt-redis-password
masterauth jxt-redis-password

# 内存管理
maxmemory 2gb
maxmemory-policy volatile-lru

# 慢日志
slowlog-log-slower-than 10000
slowlog-max-len 128

# 连接
timeout 300
tcp-keepalive 60
```

### 3.3 Redis Replica 配置

```conf
# config/redis/redis-replica.conf

bind 0.0.0.0
port 6379
protected-mode no

# 主从复制
replicaof redis-master 6379

# 密码
requirepass jxt-redis-password
masterauth jxt-redis-password

# 持久化
appendonly yes
appendfsync everysec

maxmemory 2gb
maxmemory-policy volatile-lru
```

### 3.4 Sentinel 配置（三份，仅端口不同）

```conf
# config/redis/sentinel-1.conf  (sentinel-2.conf, sentinel-3.conf 同理)

bind 0.0.0.0
port 26379
protected-mode no

# 监控 Master: mymaster 是集群名，2 是法定人数
sentinel monitor mymaster redis-master 6379 2

# Master 密码
sentinel auth-pass mymaster jxt-redis-password

# 故障转移超时
sentinel down-after-milliseconds mymaster 5000
sentinel failover-timeout mymaster 15000

# 并行同步的 Replica 数量
sentinel parallel-syncs mymaster 1

# 运行时配置持久化目录
dir /data
```

---

## 4. WVP 连接配置

### 4.1 方式一：直连 Master（不推荐）

```yaml
spring:
  data:
    redis:
      host: redis-master
      port: 6379
      password: jxt-redis-password
      database: 0          # ← 每租户不同
      timeout: 10000
```

> 缺点：Master 故障后需手动改配置或重启。

### 4.2 方式二：连接 Sentinel（推荐）

```yaml
spring:
  data:
    redis:
      password: jxt-redis-password
      database: 0          # ← 每租户不同
      timeout: 10000
      sentinel:
        master: mymaster
        nodes:
          - redis-sentinel-1:26379
          - redis-sentinel-2:26380
          - redis-sentinel-3:26381
```

> 优点：客户端自动发现 Master，故障时自动切换到新 Master，无需重启。
>
> 官方文档：Spring Data Redis 的 `LettuceConnectionFactory` 原生支持 Sentinel，配置了 `sentinel` 节点后会自动从 Sentinel 获取当前 Master 地址并建立连接。

### 4.3 多租户配置示例

```yaml
# 租户 A
spring:
  data:
    redis:
      password: jxt-redis-password
      database: 0
      timeout: 10000
      sentinel:
        master: mymaster
        nodes: sentinel-1:26379,sentinel-2:26380,sentinel-3:26381

---
# 租户 B
spring:
  data:
    redis:
      password: jxt-redis-password
      database: 1
      timeout: 10000
      sentinel:
        master: mymaster
        nodes: sentinel-1:26379,sentinel-2:26380,sentinel-3:26381

---
# 租户 C
spring:
  data:
    redis:
      password: jxt-redis-password
      database: 2
      timeout: 10000
      sentinel:
        master: mymaster
        nodes: sentinel-1:26379,sentinel-2:26380,sentinel-3:26381
```

---

## 5. 验证

### 5.1 启动验证

```bash
# 启动
docker-compose -f docker-compose-redis-sentinel.yml up -d

# 检查 Sentinel 状态
docker exec redis-sentinel-1 redis-cli -p 26379 sentinel master mymaster

# 预期输出包含:
# name: mymaster
# ip: redis-master
# port: 6379
# flags: master
# num-slaves: 1
# num-other-sentinels: 2
# quorum: 2
```

### 5.2 故障转移验证

```bash
# 1. 停掉 Master
docker stop redis-master

# 2. 等待 5~10 秒，检查 Sentinel 日志
docker logs redis-sentinel-1 | tail -20
# 预期: +sdown, +odown, +switch-master

# 3. 确认新 Master 已提升
docker exec redis-sentinel-1 redis-cli -p 26379 sentinel master mymaster
# 预期: ip 指向 redis-replica, port 6379

# 4. WVP 客户端是否自动切换（检查应用日志）
# 预期: Lettuce 日志提示 "Redis is reconnected" 或类似信息

# 5. 恢复旧 Master
docker start redis-master
# 预期: 旧 Master 自动变成新 Master 的 Replica
```

### 5.3 隔离性验证

```bash
# 写入 DB 0 (租户 A)
docker exec redis-master redis-cli -p 6379 -a jxt-redis-password -n 0 SET test-key "tenant-a"

# 查询 DB 0
docker exec redis-master redis-cli -p 6379 -a jxt-redis-password -n 0 GET test-key
# → "tenant-a"

# 查询 DB 1 (租户 B)
docker exec redis-master redis-cli -p 6379 -a jxt-redis-password -n 1 GET test-key
# → (nil)   ← DB 隔离生效

# pub/sub 跨 DB 隔离
# 终端 1: 订阅 DB 0
docker exec redis-master redis-cli -p 6379 -a jxt-redis-password -n 0 SUBSCRIBE test-channel

# 终端 2: 向 DB 1 发布
docker exec redis-master redis-cli -p 6379 -a jxt-redis-password -n 1 PUBLISH test-channel "hello"

# 终端 1: 收不到消息 ← pub/sub 隔离生效
```

---

## 6. 运维命令速查

```bash
# ========== Sentinel 管理 ==========

# 查看所有监控的 Master
redis-cli -p 26379 sentinel masters

# 查看某个 Master 详情
redis-cli -p 26379 sentinel master mymaster

# 查看某个 Master 的 Replica
redis-cli -p 26379 sentinel replicas mymaster

# 查看所有 Sentinel 节点
redis-cli -p 26379 sentinel sentinels mymaster

# 手动触发故障转移
redis-cli -p 26379 sentinel failover mymaster

# 重置 Sentinel（清理对某个 Master 的记忆）
redis-cli -p 26379 sentinel reset mymaster


# ========== 数据节点管理 ==========

# 查看复制状态
redis-cli -p 6379 -a jxt-redis-password INFO replication

# 查看当前 DB 的 key 数量
redis-cli -p 6379 -a jxt-redis-password -n 0 DBSIZE
redis-cli -p 6379 -a jxt-redis-password -n 1 DBSIZE


# ========== 监控 ==========

# Sentinel 状态
redis-cli -p 26379 PING              # → PONG
redis-cli -p 26379 INFO sentinel     # 全量 Sentinel 信息

# Master 健康
redis-cli -p 6379 -a jxt-redis-password PING  # → PONG

# 内存使用
redis-cli -p 6379 -a jxt-redis-password INFO memory | grep used_memory_human
```

---

## 7. 注意事项

| 关注点 | 说明 |
|--------|------|
| **法定人数** | `sentinel monitor mymaster redis-master 6379 2` 中的 `2` 是投票门槛。3 个 Sentinel 时，需要 ≥2 票才能触发故障转移。如果只剩 1 个 Sentinel 存活，无法自动切换 |
| **脑裂场景** | 如果 Master 只是网络隔离（不是进程死），可能出现两个 Master。Redis 的 `min-replicas-to-write` 可防止旧 Master 继续接受写入 |
| **客户端感知** | Lettuce（Spring Data Redis 默认客户端）原生支持 Sentinel，能自动处理故障转移。但使用自定义命令或事务时可能抛异常，需要应用层重试 |
| **DB 隔离确认** | 每新增租户前，确认该 DB index 无残留数据：`SELECT <N>; DBSIZE;` |
| **管理工具兼容** | 部分 Redis GUI 工具（如 Another Redis Desktop Manager）连接 Sentinel 时需要单独配置 Sentinel 地址，而非直接连 Master |
| **持久化** | 生产环境必须开启 AOF，`appendfsync everysec` 兼顾性能与安全 |

---

## 8. 常见问题

**Q: Sentinel 本身挂了怎么办？**

A: 3 个 Sentinel 中只要有 2 个存活，集群就能正常工作。挂 1 个不影响。3 个全挂的概率极低，且 Sentinel 是轻量进程，几乎不消耗资源。

**Q: 故障转移期间数据会丢吗？**

A: 异步复制场景下，最后几毫秒的写入可能丢失。如果要求零丢失，需在 Master 配置 `min-replicas-to-write 1` + `min-replicas-max-lag 10`。

**Q: 超过 16 个租户怎么办？**

A: 增加第二套 Redis Sentinel 实例（另一组 Master+Replica+3 Sentinel），新租户映射到第二套的 DB 0~15。WVP 配置只需改 `sentinel.nodes`。

**Q: 可以从现有单机 Redis 迁移到 Sentinel 吗？**

A: 可以。在现有 Redis 实例旁启 Replica 和 Sentinel，然后修改 WVP 配置从直连改为 Sentinel 连接，滚动重启即可。
