# WVP 签名密钥运维指南

> 面向运维人员:WVP sy 对接签名密钥(appKey / appSecret / sm4Key)的**生产部署、日常轮换与排障**。
>
> 一句话:密钥存在 **etcd 的一个全局 Key**(`jxt/common/wvp-signing`),WVP 与 security-management 两端都从这里读,改一次两端自动生效。

---

## 1. 背景与架构

### 1.1 这套密钥是干什么的

WVP 的 `sy.enable`(第三方平台对接)开关启用后,所有 `/api/sy/*` 接口都要做 **SM3 + SM4 签名校验**。security-management(IAM)调用 WVP 同步执法仪设备身份时,必须用这套密钥签名,WVP 用同一套密钥验签。密钥不一致 → 同步全部失败。

| 凭据 | 用途 | 备注 |
|------|------|------|
| `appKey` | 客户端标识 | 明文出现在请求 URL |
| `appSecret` | SM3 签名密钥 | **敏感**,切勿外泄 |
| `sm4Key` | SM4-ECB 加密 token 的对称密钥 | **敏感**,切勿外泄 |
| `expiresMin` | 时间戳 / token 过期窗口(分钟) | 默认 30,两端时钟偏差上限 |

> `adminToken`(WVP 内部的"管理绕过 token")**不在 etcd**:WVP 每次启动本地随机生成一个 UUID,所以这个绕过默认是**关闭**的(无人知晓该值)。如需开启固定绕过,联系开发单独加 WVP-only Key。

### 1.2 单一真相源

```
                       ┌─────────────────────────────┐
                       │  etcd: jxt/common/wvp-signing │
                       │  {"appKey","appSecret",       │
                       │   "sm4Key","expiresMin"}      │
                       └───────────┬─────────────────┘
                          Watch ▲           ▲ Watch
                                │           │
                ┌───────────────┘           └───────────────┐
                ▼                                           ▼
   WVP (Java) SySigningConfigService            security-management (Go)
        → SyTokenManager                            → SignerHolder → SignClient
        → SignAuthenticationFilter 验签              → 调用 WVP 时签名
```

- 两端都 **Watch** 同一个 Key → 改一次,两端约 1 秒内自动热加载,**无需重启**。
- 一致性由架构保证:不可能出现两端密钥不一致(这是从旧 Redis 方案迁移的核心目的)。

---

## 2. 前置条件

- etcd 集群可达(默认 `127.0.0.1:2379`,本环境容器名 `jxt-etcd`)。
- WVP 配置 `sy.enable: true`(见 `docker/wvp/wvp/application-docker.yml`)。
- security-management 的 etcd 已配置(`config/settings.yml` 的 `etcd:` 段;`wvp:` 段已移除,etcd 是唯一来源)。
- 两端系统时钟已同步(建议 NTP)——签名依赖时间戳。

---

## 3. 首次部署

### 3.1 生成并写入密钥

```bash
cd wvp-GB28181-pro/docker

# 方式 A:宿主机有 etcdctl —— 直连本机 etcd
bash init_sy_etcd_keys.sh

# 方式 B:宿主机没有 etcdctl —— 在容器内执行(本环境推荐)
bash init_sy_etcd_keys.sh --docker jxt-etcd
```

脚本会:生成新密钥 → 写入 `jxt/common/wvp-signing` → 读回校验 → 打印结果。

> **务必把 `appSecret` / `sm4Key` 记录到密码保险箱(Vault 等)。** 这两个值不会出现在任何配置文件里,etcd 是唯一存放点;一旦丢失且无人记录,只能重新轮换。

### 3.2 幂等首次部署(可选)

如果想"已存在就不动"(防止重复部署时误覆盖):

```bash
bash init_sy_etcd_keys.sh --docker jxt-etcd --if-absent
```

### 3.3 验证两端已加载

```bash
# etcd 里确实有
docker exec jxt-etcd etcdctl --endpoints=http://127.0.0.1:2379 get jxt/common/wvp-signing

# WVP 日志(应见 "配置已生效 (source=ETCD)" 和 "[SY-读取Token] 成功")
docker logs docker-polaris-wvp-1 2>&1 | grep "SY签名配置" | tail

# Security 日志(应见 "WVP SignClient enabled (source=etcd)")
docker logs security-management-security-management-1 2>&1 | grep "WVP" | tail
```

### 3.4 端到端确认(可选但推荐)

确认一次真实签名调用能通过:在 security-management 触发一次设备同步(或用其签名客户端发一个测试请求),WVP 应返回 `{"code":0,"msg":"成功"}` 而非 `{"code":2,"msg":"签名错误"}`。

---

## 4. 日常维护:密钥轮换

### 4.1 何时轮换

- 定期(建议每季度)或怀疑泄露时。
- 人员变动、密钥可能外泄时立即轮换。

### 4.2 轮换流程(零停机)

```bash
cd wvp-GB28181-pro/docker

# 重新生成并覆盖。两端 Watch 会在约 1 秒内自动热加载,无需重启。
bash init_sy_etcd_keys.sh --docker jxt-etcd
```

验证:

```bash
docker logs --since 20s docker-polaris-wvp-1            | grep "SY签名配置"
docker logs --since 20s security-management-security-management-1 | grep "WVP签名"
```

应看到两端都在刚才的几秒内打印了"配置已生效 / 配置已热加载"(appKey 已变成新值)。

> **轮换窗口**:两端 Watch 触发有亚秒级差异,期间若有签名请求,极小概率用旧 secret 签、新 secret 验 → 单次请求失败、上层有重试。低频业务可忽略;高要求场景可在业务低谷期轮换。

### 4.3 手动写指定值(不重新生成)

如需把某一套已知密钥写入(例如从备份恢复):

```bash
docker exec jxt-etcd etcdctl --endpoints=http://127.0.0.1:2379 \
  put jxt/common/wvp-signing \
  '{"appKey":"...","appSecret":"...","sm4Key":"...","expiresMin":30}'
```

### 4.4 回滚

把上一次的旧值用 4.3 的方式写回即可(同样自动热加载)。所以**每次轮换前记录旧值**,便于快速回滚。

---

## 5. 脚本参数速查(`init_sy_etcd_keys.sh`)

| 参数 | 说明 |
|------|------|
| `--docker <容器名>` | 在指定容器内执行 etcdctl(宿主机无 etcdctl 时用,如 `--docker jxt-etcd`) |
| `--endpoints <host:port>` | etcd 地址,默认 `127.0.0.1:2379` |
| `--key <路径>` | etcd Key,默认 `jxt/common/wvp-signing` |
| `--if-absent` | 仅当 Key 不存在时写入(幂等,不覆盖) |
| `--dry-run` | 只生成并打印,不写入(预演) |
| `--user/--pass` | etcd 启用鉴权后传入凭据 |
| `--cacert/--cert/--key` | etcd 启用 TLS 后传入证书 |
| `--expires-min <N>` | 过期窗口(分钟),默认 30 |

> 旧脚本 `init_sy_redis_keys.sh`(写 Redis)**已删除**;新方案不用 Redis,如需找历史版本见 git 记录。

---

## 6. 安全注意事项

- **当前 etcd 是明文、鉴权关闭**(与其它租户/数据库配置同级别)。本次方案收益是**一致性 + 持久性 + 热加载,不是保密**。生产加固路线:启用 etcd 鉴权 + mTLS(届时脚本用 `--user/--pass` 和 `--cacert/--cert/--key`)。
- `appSecret` / `sm4Key` 是敏感凭据:**只存 etcd + 密码保险箱**,绝不提交 git、不进聊天/工单。
- etcd 访问应最小授权:仅运维与两个服务账号可读 `jxt/common/wvp-signing`。
- `expiresMin` 是时钟偏差容忍上限(默认 30 分钟),值越小越严但越要求时钟同步。

---

## 7. 排障

| 现象 | 可能原因 | 处置 |
|------|----------|------|
| WVP 日志 `[SY-读取Token]失败` / `SYSTEM_ACCESS_TOKEN 读取失败` | **跑的是旧代码**(还在读 Redis) | 确认 WVP 镜像是含 `SySigningConfigService` 的新版;新版日志是 `[SY签名配置] 配置已生效` |
| 调用返回 `{"code":2,"msg":"签名错误"}` | 两端密钥不一致 / 用了旧 secret / 时钟漂移 | 比对 `etcdctl get` 的 appKey 与两端日志的 appKey 是否一致;检查 NTP |
| 调用返回 `{"code":1,"msg":"参数非法"}` | appKey 在 WVP 侧不存在(WVP 没加载到配置) | 查 WVP 日志是否 `配置已生效`;查 etcd key 是否存在 |
| 调用返回 `{"code":3,"msg":"接口己过期"}` | 请求时间戳超出 `expiresMin` 窗口 | 校时(NTP);必要时临时调大 `expiresMin` |
| 调用返回 `{"code":4,"msg":"token已过期或错误"}` | accessToken 解密失败或已过期 | sm4Key 不一致,或客户端时间漂移 |
| Security 日志 `source=yaml` 而非 `source=etcd` | etcd 未配置或 Key 缺失,降级到了 YAML(而 YAML 的 wvp 段已删) | 检查 etcd 连通性与 Key 是否存在;`wvp:` 段已从 settings.yml 移除,YAML 不再是兜底 |

### 通用诊断命令

```bash
# 1) etcd 里的密钥
docker exec jxt-etcd etcdctl --endpoints=http://127.0.0.1:2379 get jxt/common/wvp-signing

# 2) WVP 是否加载成功
docker logs docker-polaris-wvp-1 2>&1 | grep -E "SY签名配置|SY-读取Token"

# 3) Security 是否加载成功 + 来源
docker logs security-management-security-management-1 2>&1 | grep -E "WVP SignClient|WVP签名"

# 4) etcd 健康
docker exec jxt-etcd etcdctl --endpoints=http://127.0.0.1:2379 endpoint health
```

---

## 8. 上线检查清单

首次部署 / 每次轮换后逐项确认:

- [ ] `etcdctl get jxt/common/wvp-signing` 返回完整 JSON
- [ ] WVP 日志出现 `[SY签名配置] 配置已生效 (source=ETCD)`、`[SY-读取Token] 成功`
- [ ] Security 日志出现 `WVP SignClient enabled (source=etcd)`
- [ ] 一次真实签名调用返回 `code:0`(非 `code:2 签名错误`)
- [ ] `appSecret` / `sm4Key` 已记入密码保险箱
- [ ] 两端时钟同步(NTP 正常)

---

## 9. 相关文件

| 文件 | 作用 |
|------|------|
| `docker/init_sy_etcd_keys.sh` | **本指南配套脚本**:生成 + 写 etcd + 校验 |
| `docs/wvp-appkey-secret-analysis.md` | 技术细节:生成 / 存储 / 加载 / 校验 / 生命周期 |
| WVP `SySigningConfigService.java` | jetcd 读取 + Watch |
| Security `common/wvp/signing_config_etcd.go` | clientv3 读取 + Watch |
