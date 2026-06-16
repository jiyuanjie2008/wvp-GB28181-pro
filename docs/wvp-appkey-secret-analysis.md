# WVP appKey / appSecret 生成与管理机制分析

> 本文分析 WVP-GB28181-Pro 在 `sy.enable`(第三方平台对接)集成中,appKey / appSecret 等签名凭据的**生成、存储、加载、校验与生命周期**。
>
> **现状(2026-06 迁移后):凭据以单一 JSON 存于 etcd 的全局 Key `jxt/common/wvp-signing`,WVP(Java)与 security-management(Go)两端都从该 Key 读取,并通过 Watch 实现**轮换热加载**。一致性由架构保证——两端不可能读到不同的值。**
>
> 历史背景:此前凭据存在 Redis(`SYSTEM_APPKEY` 等 4 个 Key),WVP 启动时读取;security-management 则从本地 `settings.yml` 读另一份,靠人工保证一致。该方案在 Sentinel HA 改造后失效(Redis Key 未重新写入,WVP 拿不到凭据,IAM→WVP 同步断链),遂迁移到 etcd 单一真相源。迁移细节见本文 §8。

---

## 1. 涉及的凭据

`sy.enable` 集成使用 **4 个值**,以及一个 WVP 本地的管理绕过 token:

| 凭据 | 用途 | 在网络上是否明文 | 存放位置 |
|------|------|------------------|----------|
| `appKey` | 客户端标识,出现在请求 URL query | 是(明文) | etcd `jxt/common/wvp-signing` |
| `appSecret` | **SM3 签名密钥**,参与签名拼接 | 否(仅两端持有) | etcd `jxt/common/wvp-signing` |
| `sm4Key` | SM4-ECB 对称密钥,加解密 `accessToken` | 否(仅两端持有) | etcd `jxt/common/wvp-signing` |
| `expiresMin` | 时间戳 + token 过期时间(分钟) | 是 | etcd `jxt/common/wvp-signing` |
| `adminToken` | WVP 内置"管理绕过 token",命中即放行(调试用) | 是 | **不进 etcd**:WVP 启动时本地生成随机 UUID(见 §3) |

> appKey/appSecret 是成对的全局共享凭据——所有租户、所有设备同步请求都用同一对(多租户只隔离 `apiUrl`/`realm`,不隔离签名密钥)。

---

## 2. 生成(离线,运维侧)

凭据由部署脚本 `docker/generate_sy_keys.sh` 用 `openssl rand` 离线生成:

```bash
SM4_KEY    =$(openssl rand -hex 16)   # 32 hex 字符
APP_KEY     =$(openssl rand -hex 16)
APP_SECRET  =$(openssl rand -hex 32)
ADMIN_TOKEN =$(openssl rand -hex 32)   # 仅旧 Redis 方案用;新方案 WVP 本地生成,无需此值
```

两端进程内没有任何生成逻辑。生成后写入 etcd(见 §3),两端读取。

---

## 3. 存储(etcd 单一真相源)

凭据以**单个 JSON 对象**存在 etcd 的全局 Key 下:

```
key:   jxt/common/wvp-signing          # 全局,放在已有 common/ 前缀下(与 common/storage-site、common/resolver 同级)
value: {
  "appKey":     "<32 hex>",
  "appSecret":  "<64 hex>",
  "sm4Key":     "<32 hex>",
  "expiresMin": 30
}
```

写入(运维一次性):

```bash
docker exec jxt-etcd etcdctl --endpoints=http://127.0.0.1:2379 \
  put jxt/common/wvp-signing \
  '{"appKey":"...","appSecret":"...","sm4Key":"...","expiresMin":30}'
```

要点:

- **命名空间差异(已核实,实现时踩过的坑)**:WVP 的 jetcd 客户端构建时应用 `.namespace("jxt/")`,自动剥离前缀,故读裸 Key `common/wvp-signing`;Go 的 `clientv3` **不**自动剥离(namespace 只传给了 jxt-core Provider),故 Go 端读**完整 Key** `jxt/common/wvp-signing`。两端指向同一物理 Key。
- **adminToken 不进 etcd**:它是 WVP 内部专用(Go 端从不使用),放进共享 JSON 既污染契约又会随密钥轮换让在途绕过请求失效。改为 WVP 首次成功加载签名配置时**本地生成随机 UUID** 填入 `SyTokenManager.adminToken`,使 SignAuthenticationFilter 的"管理绕过"默认不可用(无人知晓该值)。若将来需要固定绕过 token,再加 WVP-only 的 etcd Key。
- **安全姿态**:etcd 当前**鉴权关闭、明文 HTTP**(与现有所有 etcd 配置同安全级别)。本次迁移的收益是**一致性 / 持久性 / Watch 热加载,而非保密**。启用 etcd 鉴权 + TLS 是后续独立加固任务(见 §9)。

---

## 4. 加载与热加载

两端各有自己的 etcd 读取器,但读的是同一个 Key,算法一致。

### 4.1 WVP(Java / Spring Boot)

**新增 `web/custom/conf/SySigningConfigService.java`**(镜像 `jxt/tenant/TenantConfigService.java` 的 jetcd load+watch 范式):

- `@Component` + `@ConditionalOnProperty(sy.enable=true)`(与 `SignAuthenticationFilter` 同一开关)。
- 只注入 `EtcdProperties`(`jxt.etcd.endpoints` / `namespace`)——**不注入 `TenantProperties`**,因此**不被 `jxt.tenant.code` 门控**(签名配置是全局的)。
- `@PostConstruct`:构建 jetcd `Client`(namespace `jxt/`)→ `loadFromEtcd()` → `startWatch()`。
- `loadFromEtcd()`:`kvClient.get("common/wvp-signing")`;空则返回 `false`(走重试);否则 fastjson2 解析 → `applyConfigValue()` → 写入 `SyTokenManager`,并记录 `lastRevision`。
- `startWatch()`:`watchClient.watch("common/wvp-signing", withRevision(lastRevision+1))`——**gap-free**,从加载 revision 之后续看,不漏改动。`onNext` PUT → 重新 apply;DELETE → 保留最后已知良好值(不清空);`onError` → 全量重载。
- `applyConfigValue()`:`SyTokenManager.appMap` clear+put(支持 appKey 轮换)、`sm4Key`、`expires`;adminToken 仅首次生成 UUID。
- `@PreDestroy`:关 watcher + client。

**`web/custom/service/CameraChannelService.java`**:删除了原 `refreshToken()` 里全部 4 个 Redis 读取;`run()` 的 30s 重试循环保留,改为判定 `signingConfigService.isLoaded()`(etcd 启动时可能短暂不可达时的优雅降级)。`redisTemplate*` 字段保留(其它方法仍在用 Redis)。

### 4.2 security-management(Go)

**核心难点与解法**:DI 封装 Uber `dig`(`common/di`),在消费者(如 `SyncDispatcher`)构造时一次性解析 `wvp.RequestSigner` 并持有为接口字段;`di.Provide` 无法在运行时热替换。全仓**无** `.(*SignClient)` 类型断言(已 grep 确认),故用一个实现 `RequestSigner` 的 `SignerHolder` 透明替换,消费者零改动。

- **新增 `common/wvp/signer_holder.go`**:`atomic.Pointer[RequestSigner]` 持有当前 signer;`Store` 原子替换、`Sign` 委托、nil→`NoopSigner`。注册进 DI 一次,消费者持 holder;Watch 通过 `holder.Store` 热切换,在途 `Sign` 调用立即可见。
- **新增 `common/wvp/signing_config_etcd.go`**:`SigningConfigSource` 复用 `tenantdb.GetGlobalCache().GetEtcdClient()`(不新开连接);单 Key 不引入 jxt-core `provider.Provider`(对单 Key 过重)。`LoadOnce` 一次性 Get;`StartWatch` 先 gap-free Get 再从 revision+1 续看;PUT→重建 `*SignClient` 并 `holder.Store`;DELETE→`NoopSigner`(对齐"缺失即降级");err→`LoadOnce` 后重启 Watch。
- **改 `cmd/api/wvp.go`**:建 `holder` → **优先 etcd**(`GetGlobalCache()` 非空则 `LoadOnce` + `StartWatch`,即使首次未命中也启 Watch 以捕获 boot 后写入)→ **YAML 兜底**(etcd 未加载时从 `settings.yml` 的 `wvp:` 段构建)→ 一次性 `di.Provide(func() RequestSigner{ return holder })`。`server.go` 优雅关闭时 cancel Watch(在 etcd client 关闭前)。

---

## 5. 校验使用(WVP 端 `SignAuthenticationFilter`)

**未改动**。所有 `/api/sy/*` 请求仍经 `SignAuthenticationFilter`(`@ConditionalOnProperty(sy.enable=true)`),它从 `SyTokenManager` 取 secret/sm4Key/expires/adminToken。流程不变:

```
请求到达 /api/sy/*
    ├── 1. 必填参数 (sign/appKey/accessToken/timestamp)        → 缺则 code:1
    ├── 2. SyTokenManager.appMap 查 appKey → secret              → 无则 code:1
    ├── 3. 计算 SM3 签名比对 sign                                → 不等则 code:2
    ├── 4. timestamp 过期检查 (expires 分钟)                    → 过期则 code:3
    └── 5. accessToken:== adminToken 放行;否则 SM4 解密查 expirationTime → 过期 code:4
```

签名算法:**所有 query 参数按字母序 key+value 拼接 → 追加 POST body → 追加 secret → SM3**。Go `computeSign` 与 Java filter 逐字节一致。安全细节:验签失败日志只记 appKey/URI/sign 长度,**严禁记录含 secret 的待签串**。

---

## 6. 生命周期与轮换(关键改进)

| 场景 | 行为 |
|------|------|
| 任一端启动、etcd Key 齐全 | `LoadOnce`/`loadFromEtcd` 成功,缓存进内存 |
| 启动时 etcd Key 缺失 | Go: holder 保持 Noop;WVP: 30s 重试直到出现。两端 Watch 已启动 |
| **轮换(改 etcd Key)** | **两端 Watch 在 ~1 秒内自动跟随,无需重启**(已实测:改 `expiresMin`,两端同一秒刷新) |
| 删除 etcd Key | Go→Noop;WVP→保留最后已知良好值(不清空),日志告警 |
| appKey 轮换 | WVP `appMap` clear+put;Go holder.Store 新 SignClient |

> 这是相比旧 Redis 方案最大的改进:旧方案"轮换必须重启 WVP",且两端各持一份易不一致;新方案单次 `etcdctl put` 即可原子轮换两端。

---

## 7. 端到端验证(已实测)

1. `etcdctl get jxt/common/wvp-signing` 返回 JSON。
2. security-management 启动日志:`WVP SignClient enabled (source=etcd)`、`[WVP签名] 从 ETCD 加载成功`。
3. WVP 启动日志:`[SY签名配置] 配置已生效 (source=ETCD)`、`[SY-读取Token] 成功`,且不再有 `SYSTEM_ACCESS_TOKEN 读取失败`。
4. **真实签名调用(一致性证明)**:用 Go `SignClient`(etcd 现值)对 `GET /api/sy/camera/list` 签名 → WVP 返回 **HTTP 200 `{"code":0,"msg":"成功",...}`**(非 `code:2 签名错误`)→ 签名校验通过、两端密钥一致。
5. **热加载(实测)**:`etcdctl put` 改值 → 两端同一秒日志刷新(Watch `onNext`)。
6. **优雅降级**:删 Key → Go 回 Noop、WVP 保留最后值并告警;重写后两端恢复。

---

## 8. 从 Redis 迁移到 etcd 的来龙去脉

- **旧方案**:WVP 从 Redis 读 `SYSTEM_APPKEY`/`SYSTEM_SM4_KEY`/`SYSTEM_ACCESS_TOKEN`/`sys_INTERFACE_VALID_TIME`(脚本 `docker/init_sy_redis_keys.sh` 写入);security-management 从 `settings.yml` 读另一份。
- **失效**:Sentinel HA 改造后,WVP 改读共享 Redis,但 4 个 Key 未重新写入 → WVP 启动后每 30s 报 `SYSTEM_ACCESS_TOKEN 读取失败`,`SignAuthenticationFilter` 对所有 `/api/sy/*` 返回 `code:1`,IAM→WVP 设备同步断链。
- **迁移**:把凭据集中到 etcd 单一 Key,两端读取 + Watch。Redis 读取代码已从 WVP 移除;security-management 的 `settings.yml` 的 `wvp:` 段保留为**本地开发兜底**(值与 etcd 相同,无冲突)。

---

## 9. 安全注意事项与能力边界

- **明文 etcd**:本次接受(auth 关、TLS 延后)。收益是一致/持久/Watch,非保密。生产建议后续启用 etcd 鉴权 + mTLS,或把 `appSecret`/`sm4Key` 迁到专用密钥管理(Vault/OpenBao 等)。
- `appSecret`/`sm4Key` 属敏感凭据,**不要提交到版本控制**;生产建议环境变量或密钥管理注入。
- 签名校验依赖 `timestamp`,两端系统时钟必须同步(建议 NTP),最大偏差由 `expiresMin` 控制(默认 30 分钟)。
- `SyTokenManager.appMap` 是普通 HashMap,轮换时 clear+put 与 filter 并发读未同步;轮换极低频,最差读到空 map(返回"参数非法")或新值,均安全。后续可换 `ConcurrentHashMap` 加固。

**当前已具备的能力**:etcd 集中存储、两端一致、Watch 热加载轮换、优雅降级、gap-free Watch、单次 `etcdctl put` 原子轮换。
**仍未具备**:etcd 鉴权/TLS、密钥静态加密、访问审计、自动轮换/租约——均为后续可选项。

---

## 10. 关键文件索引

### WVP(Java)
| 文件 | 作用 |
|------|------|
| `web/custom/conf/SySigningConfigService.java` | **新增**:jetcd 加载 + Watch `common/wvp-signing`,写入 SyTokenManager |
| `web/custom/service/CameraChannelService.java` | 移除 Redis 读取,`run()` 重试委托给 SySigningConfigService |
| `web/custom/conf/SyTokenManager.java` | 内存持有者(不改);adminToken 现为本地 UUID |
| `web/custom/conf/SignAuthenticationFilter.java` | `/api/sy/*` 验签(不改) |
| `jxt/tenant/TenantConfigService.java` | jetcd load+watch 范式(供镜像) |

### security-management(Go)
| 文件 | 作用 |
|------|------|
| `common/wvp/signer_holder.go` | **新增**:`atomic.Pointer[RequestSigner]`,透明热替换 |
| `common/wvp/signing_config_etcd.go` | **新增**:etcd 单 Key Get+Watch,推 holder |
| `common/wvp/sign_config.go` | 加 `SignConfig.Valid()`(YAML 兜底路径仍支持) |
| `common/wvp/sign_client.go` | `NewSignClient`/`Sign`/SM3+SM4(不改) |
| `cmd/api/wvp.go` | 改走 holder + etcd 主源 + YAML 兜底 |
| `cmd/api/server.go` | 优雅关闭 Watch |
| `common/tenantdb/cache.go` | `GetEtcdClient()`/`GetGlobalCache()` 复用 |
