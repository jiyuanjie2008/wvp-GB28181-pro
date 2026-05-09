# WVP 单实例单租户设计方案

> 文档编号: dp-2026-05-09-wvp-multi-tenant-design
> 日期: 2026-05-09
> 状态: Draft
> 作者: jiyuanjie

---

## 1. 背景与动机

### 1.1 现状

WVP-GB28181-pro 当前没有租户概念，系统包含：
- **RBAC 用户模型**：`wvp_user` / `wvp_user_role` 表，仅限简单的角色-权限控制
- **serverId**：`user-settings.server-id`（默认 `"000000"`），用于 WVP 集群内节点标识和 Redis key 命名空间
- **sip.id**：20 位 GB/T 28181 国标编号，唯一标识一个信令服务器及其域
- **共享 Redis**：所有 WVP 实例共用同一 Redis database index，通过 `serverId` 后缀区分 key

### 1.2 目标

在不修改 WVP 源码的前提下，将每个 WVP 实例作为一个独立租户运行，实现：

1. **终端接入隔离**：不同租户的设备通过不同的 SIP 服务 ID 接入
2. **数据存储隔离**：数据库层面靠 `server_id` 列区分租户数据
3. **缓存隔离**：Redis 层面靠 database index 区分租户，消除 pub/sub 消息泄露
4. **鉴权隔离**：在网关层做租户级别路由和鉴权

### 1.3 适用范围

- 租户数 ≤ 16 的场景（受 Redis database index 上限约束，超过需切换方案）
- 每个租户需独立的 20 位 GB/T 28181 SIP ID

---

## 2. 架构总览

```
                            ┌─────────────────┐
                            │   前端 / API      │
                            └────────┬────────┘
                                     │
                            ┌────────▼────────┐
                            │   网关 (Nginx)    │  ← 根据域名/Header 路由到对应 WVP 实例
                            │   租户级鉴权      │
                            └────────┬────────┘
                                     │
              ┌──────────────────────┼──────────────────────┐
              │                      │                      │
     ┌────────▼────────┐    ┌───────▼────────┐    ┌───────▼────────┐
     │  WVP 实例 A      │    │  WVP 实例 B     │    │  WVP 实例 C     │
     │  port: 18080     │    │  port: 28080    │    │  port: 38080    │
     │  sip port: 15060 │    │  sip port: 25060│    │  sip port: 35060│
     │                  │    │                  │    │                  │
     │  serverId: A     │    │  serverId: B     │    │  serverId: C     │
     │  sip.id: 租户A   │    │  sip.id: 租户B   │    │  sip.id: 租户C   │
     │  sip.domain:     │    │  sip.domain:     │    │  sip.domain:     │
     │  A的国标编码     │    │  B的国标编码     │    │  C的国标编码     │
     │                  │    │                  │    │                  │
     │  Redis DB 0      │    │  Redis DB 1      │    │  Redis DB 2      │
     └────────┬─────────┘    └───────┬──────────┘    └──────┬──────────┘
              │                      │                      │
              │    ┌─────────────────┼──────────────────────┤
              │    │                 │                      │
     ┌────────▼────▼─────────────────▼──────────────────────▼──────┐
     │                     MySQL / PostgreSQL                       │
     │                                                              │
     │  所有租户数据共库，server_id 列区分:                          │
     │  ┌────────────┬──────────────────────────────┐               │
     │  │  表名       │   server_id 列              │               │
     │  ├────────────┼──────────────────────────────┤               │
     │  │ wvp_device │  所属信令服务器ID (已有)      │               │
     │  │ wvp_platform│ 所属信令服务器ID (已有)      │               │
     │  │ wvp_stream_push│ 所属信令服务器ID (已有)   │               │
     │  │ wvp_stream_proxy│ 所属信令服务器ID (已有)  │               │
     │  │ wvp_cloud_record│ 所属信令服务器ID (已有)  │               │
     │  │ wvp_media_server│ 对应信令服务器ID (已有)  │               │
     │  └────────────┴──────────────────────────────┘               │
     └──────────────────────────────────────────────────────────────┘
```

---

## 3. 详细设计

### 3.1 部署模型

**策略：一个租户一个 WVP JVM 进程**

每个 WVP 实例独立部署，通过端口和配置区分：

| 配置项 | 租户 A | 租户 B | 租户 C |
|--------|--------|--------|--------|
| HTTP 端口 | 18080 | 28080 | 38080 |
| SIP 端口 | 15060 | 25060 | 35060 |
| `serverId` | tenant-a | tenant-b | tenant-c |
| `sip.id` | 44010200492000000001 | 44010200492000000002 | 44010200492000000003 |
| `sip.domain` | 4401020049 | 4401020049 | 4401020049 |
| Redis database | 0 | 1 | 2 |

**运行方式**：

```bash
# 租户 A
java -jar wvp.jar --spring.config.location=config/tenant-a/application.yml \
                  --server.port=18080

# 租户 B
java -jar wvp.jar --spring.config.location=config/tenant-b/application.yml \
                  --server.port=28080
```

或使用 Docker Compose 管理多实例：

```yaml
services:
  wvp-tenant-a:
    image: wvp:latest
    ports:
      - "18080:18080"
      - "15060:5060/udp"
    volumes:
      - ./config/tenant-a:/app/config
  wvp-tenant-b:
    image: wvp:latest
    ports:
      - "28080:28080"
      - "25060:5060/udp"
    volumes:
      - ./config/tenant-b:/app/config
```

**端口规划建议**：

| 租户序号 | HTTP | SIP (UDP) | 公式 |
|----------|------|-----------|------|
| 1 | 18080 | 15060 | 10000 + 租户序号 × 10000 + 基础端口 |
| 2 | 28080 | 25060 | |
| n | n8080 | n5060 | |

> 注：SIP 端口复用 5060 作为容器内端口即可，宿主机每个租户需不同端口以免 UDP 冲突。

### 3.2 数据库隔离

**策略：共享数据库实例，`server_id` 列区分**

**现状分析**：

当前 WVP 数据库中所有主要实体表已包含 `server_id` 列：

```sql
-- wvp_device
server_id character varying(50) COMMENT '所属信令服务器ID'

-- wvp_platform
server_id character varying(50) COMMENT '所属信令服务器ID'

-- wvp_stream_push
server_id character varying(50) COMMENT '所属信令服务器ID'

-- wvp_stream_proxy
server_id character varying(50) COMMENT '所属信令服务器ID'

-- wvp_cloud_record
server_id character varying(50) COMMENT '所属信令服务器ID'

-- wvp_media_server
server_id character varying(50) COMMENT '对应信令服务器ID'
```

**无需修改**：WVP 在写入数据时会自动填充当前实例的 `serverId`，查询时也会根据 `serverId` 过滤。这是 WVP 集群模式的原生行为。

**建议优化**（可选）：

```sql
-- 为高频查询表添加复合索引
CREATE INDEX idx_device_server_id ON wvp_device(server_id);
CREATE INDEX idx_platform_server_id ON wvp_platform(server_id);
CREATE INDEX idx_stream_push_server_id ON wvp_stream_push(server_id);
CREATE INDEX idx_stream_proxy_server_id ON wvp_stream_proxy(server_id);
CREATE INDEX idx_cloud_record_server_id ON wvp_cloud_record(server_id);
```

**数据隔离验证**：

```sql
-- 验证每个租户只看到自己的数据
-- 以设备表为例
SELECT server_id, COUNT(*) FROM wvp_device GROUP BY server_id;
-- 期望：每个 server_id 只包含该租户的设备
```

### 3.3 Redis 隔离

**策略：每个租户独立 Redis database index**

**配置方式**：

```yaml
# config/tenant-a/application.yml
spring:
  data:
    redis:
      host: 127.0.0.1
      port: 6379
      database: 0    # 租户 A 使用 DB 0
      password: xxx
      timeout: 10000

---
# config/tenant-b/application.yml
spring:
  data:
    redis:
      host: 127.0.0.1
      port: 6379
      database: 1    # 租户 B 使用 DB 1
      password: xxx
      timeout: 10000
```

**隔离机制**：

Redis database index 是 Redis 内置的逻辑隔离机制：
- 不同 DB 之间的 key 完全隔离（SELECT 切换后不可见）
- **pub/sub 频道也按 DB 隔离**（这是关键）—— DB 0 的 `SUBSCRIBE VM_MSG_GPS` 不会收到 DB 1 的 `PUBLISH VM_MSG_GPS`
- 因此 WVP 的所有 pub/sub 消息（GPS、报警、流状态变更等）天然不会跨租户泄露

**双重隔离**：

即使同一 DB 内（同租户内多个 WVP 集群节点），key 也已经通过 `serverId` 后缀隔离：

```
Redis DB 0 (租户 A)
├── VMP_SIP_CSEQ_tenant-a-node1
├── VMP_SIP_CSEQ_tenant-a-node2          ← 同租户不同节点，key 自动区分
├── VMP_DEVICE_INFO (hash, 全局)
├── VMP_DEVICE_KEEPALIVE:device-001
├── VMP_SERVER_LIST (zset)
├── VMP_SIGNALLING_SERVER_INFO_tenant-a-node1
└── ...

Redis DB 1 (租户 B)
├── VMP_SIP_CSEQ_tenant-b-node1          ← 不同租户，完全隔离
├── VMP_DEVICE_KEEPALIVE:device-002
└── ...
```

**约束**：Redis 单个实例只有 16 个 DB (0~15)，最多承载 16 个租户。超过需考虑：
- 方案 A：多 Redis 实例（每个实例 16 个租户）
- 方案 B：改造 pub/sub 频道名加 `serverId` 前缀（需修改 WVP 源码）

### 3.4 鉴权与路由

**策略：WVP 内部 RBAC 不动，租户隔离在网关层实现**

**网关层职责**：

```
用户请求
    │
    ▼
┌─────────────────────┐
│ Nginx / API Gateway  │
│                      │
│ 1. 解析租户标识       │  ← 从域名 (tenant-a.example.com)
│ 2. 验证用户身份       │     或 Header (X-Tenant-Id: tenant-a)
│ 3. 检查用户-租户绑定  │     或 URL path (/tenant-a/api/...)
│ 4. 路由到对应 WVP     │
│                      │
└─────────┬───────────┘
          │
          ▼
  WVP 实例 (对应租户)
```

**Nginx 路由配置示例**：

```nginx
# 基于域名的路由
server {
    server_name tenant-a.video.example.com;
    
    location / {
        proxy_pass http://127.0.0.1:18080;   # WVP 租户 A
        proxy_set_header X-Tenant-Id tenant-a;
    }
}

server {
    server_name tenant-b.video.example.com;
    
    location / {
        proxy_pass http://127.0.0.1:28080;   # WVP 租户 B
        proxy_set_header X-Tenant-Id tenant-b;
    }
}
```

或基于路径前缀：

```nginx
server {
    server_name video.example.com;
    
    location /tenant-a/ {
        rewrite ^/tenant-a/(.*) /$1 break;
        proxy_pass http://127.0.0.1:18080;
    }
    
    location /tenant-b/ {
        rewrite ^/tenant-b/(.*) /$1 break;
        proxy_pass http://127.0.0.1:28080;
    }
}
```

**用户-租户绑定**（在你们现有的 security-management / tenant-service 中管理）：

```sql
-- 示意：在 security-management 中维护
CREATE TABLE user_tenant_binding (
    user_id    VARCHAR(64) NOT NULL,
    tenant_id  VARCHAR(64) NOT NULL,
    server_id  VARCHAR(50) NOT NULL,   -- 对应的 WVP serverId
    PRIMARY KEY (user_id, tenant_id)
);
```

网关验证 JWT/Token 后，检查用户是否有权访问目标租户，再转发到对应 WVP 实例。

### 3.5 SIP 终端接入

**策略：通过 `sip.id` 区分不同租户的终端接入**

每个租户的 WVP 实例配置独立的 `sip.id`（20 位 GB/T 28181 国标 ID）：

```yaml
# 租户 A
sip:
  ip: 0.0.0.0
  port: 15060
  domain: 4401020001          # 租户 A 的前 10 位编码
  id: 44010200012000000001    # 租户 A 的完整 20 位 ID
  password: tenant-a-sip-pwd

# 租户 B
sip:
  ip: 0.0.0.0
  port: 25060
  domain: 4401020002          # 租户 B 的前 10 位编码
  id: 44010200022000000001    # 租户 B 的完整 20 位 ID
  password: tenant-b-sip-pwd
```

**GB/T 28181 终端注册流程**：

```
┌──────────┐         ┌──────────────┐         ┌──────────┐
│ 摄像头    │         │ WVP (租户A)   │         │  Redis A  │
│ (设备)    │         │              │         │  (DB 0)   │
└────┬─────┘         └──────┬───────┘         └────┬─────┘
     │  1. REGISTER          │                      │
     │  sip:44010200012000000001@租户A_IP:15060      │
     │─────────────────────▶│                      │
     │                      │  2. 存储设备信息       │
     │                      │  (serverId=tenant-a)  │
     │                      │─────────────────────▶│
     │  3. 401 Unauthorized │                      │
     │  (WWW-Authenticate)  │                      │
     │◀─────────────────────│                      │
     │  4. REGISTER (带鉴权) │                      │
     │─────────────────────▶│                      │
     │                      │  5. 更新在线状态       │
     │                      │  VMP_DEVICE_KEEPALIVE │
     │                      │─────────────────────▶│
     │  6. 200 OK           │                      │
     │◀─────────────────────│                      │
```

设备配置时，只需指定对应租户的 SIP 服务器 IP:Port 和 SIP ID 即可，和普通 WVP 配置一致。

---

## 4. 配置文件模板

### 4.1 租户 A 配置

```yaml
# config/tenant-a/application.yml

server:
  port: 18080

spring:
  datasource:
    url: jdbc:mysql://127.0.0.1:3306/wvp?useUnicode=true&characterEncoding=utf8
    username: wvp
    password: wvp_password
  data:
    redis:
      host: 127.0.0.1
      port: 6379
      database: 0
      password: redis_password
      timeout: 10000

sip:
  ip: 0.0.0.0
  port: 15060
  domain: 4401020001
  id: 44010200012000000001
  password: tenant_a_sip_password

user-settings:
  server-id: tenant-a       # 对应数据库 server_id 列和 Redis key 前缀
```

### 4.2 租户 B 配置

```yaml
# config/tenant-b/application.yml

server:
  port: 28080

spring:
  datasource:
    url: jdbc:mysql://127.0.0.1:3306/wvp?useUnicode=true&characterEncoding=utf8
    username: wvp
    password: wvp_password
  data:
    redis:
      host: 127.0.0.1
      port: 6379
      database: 1       # ← 唯一差异
      password: redis_password
      timeout: 10000

sip:
  ip: 0.0.0.0
  port: 25060
  domain: 4401020002
  id: 44010200022000000001          # ← 唯一
  password: tenant_b_sip_password

user-settings:
  server-id: tenant-b
```

---

## 5. 运维考量

### 5.1 租户新增流程

1. 分配新的 `serverId`（如 `tenant-x`）
2. 分配新的 20 位 GB/T 28181 `sip.id` 和 10 位 `sip.domain`
3. 选择未使用的端口（HTTP + SIP UDP）
4. 选择未使用的 Redis database index（0~15）
5. 创建配置文件 `config/tenant-x/application.yml`
6. 启动新 WVP 实例
7. 在网关添加路由规则
8. 通知终端设备更新 SIP 服务器地址

### 5.2 租户下线流程

1. 先在网关摘除路由（拒绝新请求）
2. 等待现有 SIP 会话超时（`sip.timeout` 配置，默认 3 分钟）
3. 停止 WVP 实例
4. 数据库数据保留（`server_id` 标识），后续可清理：
   ```sql
   DELETE FROM wvp_device WHERE server_id = 'tenant-x';
   DELETE FROM wvp_platform WHERE server_id = 'tenant-x';
   -- ... 其他表
   ```
5. Redis DB 内数据用 `FLUSHDB`（仅清当前 DB）或等待 TTL 自然过期

### 5.3 监控

每个 WVP 实例暴露独立的监控端点：

```
租户 A: http://127.0.0.1:18080/actuator/health
租户 B: http://127.0.0.1:28080/actuator/health
```

建议监控指标：
- JVM 内存 / GC 频率
- 在线设备数：`HLEN VMP_DEVICE_INFO`（各 DB 分别查）
- SIP 注册成功率（应用日志）
- Redis 连接数和延迟

### 5.4 资源估算

以 3 个租户为例：

| 资源 | 单实例 | 3 租户合计 |
|------|--------|------------|
| CPU | 2 cores | 6 cores |
| 内存 | 2 GB | 6 GB |
| Redis 连接 | ~10 个 | ~30 个 |
| 数据库连接 | 10~20 个 | 30~60 个 |

---

## 6. 限制与风险

| 限制 | 影响 | 缓解措施 |
|------|------|----------|
| Redis DB index 上限 16 | 最多 16 个租户/Redis 实例 | 超过后使用多 Redis 实例扩展 |
| 数据库无租户级索引 | `server_id` 过滤可能慢 | 为 `server_id` 列建复合索引 |
| 运维复杂度随租户数增长 | 每新增租户需新增配置和进程 | 编写 Ansible/Docker Compose 模板化管理 |
| pub/sub 频道代码级隔离 | 严重依赖 Redis DB 隔离正确性 | 启动后做冒烟测试验证消息不泄露 |
| WVP 升级需同步所有实例 | 升级窗口对所有租户相同 | 使用 Docker 镜像版本管理，Jenkins 批量更新 |
| 网关成为单点 | 网关故障影响所有租户 | Nginx 做高可用，或使用云 API 网关 |

---

## 7. 扩展路径

当租户数超过 16 个或需要更高级的隔离时：

1. **多 Redis 实例**：`redis-cluster://` 或独立 Redis 进程
2. **独立数据库**：每个租户独立 MySQL/PostgreSQL schema
3. **源码级改造**：如果未来需要单 WVP 实例服务多租户，修改点包括 ——
   - `UserSetting` 支持动态 `serverId`（从请求上下文获取）
   - Redis key 前缀注入（当前硬编码 `userSetting.getServerId()`）
   - 数据库查询统一加 `server_id` 过滤（当前已有，但需审计完整性）
   - pub/sub 频道名加 tenant 前缀（需修改 `VideoManagerConstants`）

---

## 8. 附录

### 8.1 相关代码文件

| 文件 | 角色 |
|------|------|
| `src/main/java/com/genersoft/iot/vmp/conf/UserSetting.java` | serverId 定义 |
| `src/main/java/com/genersoft/iot/vmp/conf/SipConfig.java` | SIP ID / domain 定义 |
| `src/main/java/com/genersoft/iot/vmp/common/VideoManagerConstants.java` | Redis key 常量 |
| `src/main/java/com/genersoft/iot/vmp/storager/impl/RedisCatchStorageImpl.java` | Redis 存储实现 |
| `src/main/java/com/genersoft/iot/vmp/conf/redis/RedisRpcConfig.java` | Redis RPC 配置 |
| `src/main/resources/application-dev.yml` | 开发环境 Redis 配置 |
| `数据库/2.7.4/初始化-mysql-2.7.4.sql` | 数据库表结构 |

### 8.2 冒烟测试清单

新增租户后的验证步骤：

- [ ] WVP 实例正常启动，健康检查通过
- [ ] Redis 连接成功，key 前缀包含正确的 `serverId`
- [ ] 设备注册：摄像头能成功 REGISTER 到该租户的 SIP 端口
- [ ] 设备仅在所属租户的 Redis DB 中出现（另一个 DB 中查不到）
- [ ] 视频流播放正常（HTTP 和 WebRTC）
- [ ] 网关路由正确：用租户 A 域名访问只看到租户 A 的设备
- [ ] 跨租户查询隔离：租户 A 的 API 不能返回租户 B 的设备
- [ ] Redis FLUSHDB 测试：清除租户 A 的 DB 0 不影响租户 B 的 DB 1
