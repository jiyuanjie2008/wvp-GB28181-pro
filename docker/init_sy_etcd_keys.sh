#!/bin/bash
# =============================================================================
# init_sy_etcd_keys.sh
# 生成 WVP sy 对接签名密钥并写入 etcd(单一真相源)
# -----------------------------------------------------------------------------
# 用途:
#   - 首次部署:生成密钥,写入 etcd key jxt/common/wvp-signing
#   - 密钥轮换:重新生成并覆盖(两端 Watch 自动热加载,无需重启 WVP/Security)
#
# 完整运维说明见: docs/maintenance/wvp-signing-keys.md
#
# 用法示例:
#   # 1) 直连本机 etcd,生成并写入(最常见)
#   bash init_sy_etcd_keys.sh
#
#   # 2) 宿主机没有 etcdctl —— 在容器内执行
#   bash init_sy_etcd_keys.sh --docker jxt-etcd
#
#   # 3) 指定 etcd 地址
#   bash init_sy_etcd_keys.sh --endpoints 10.0.0.5:2379
#
#   # 4) 幂等首次部署:仅当 key 不存在时写入(不覆盖现有)
#   bash init_sy_etcd_keys.sh --if-absent
#
#   # 5) 只看不写(预演)
#   bash init_sy_etcd_keys.sh --dry-run
#
#   # 6) etcd 启用鉴权后
#   bash init_sy_etcd_keys.sh --user root --pass 'yourpass'
#
#   # 7) etcd 启用 TLS 后
#   bash init_sy_etcd_keys.sh --cacert /etc/etcd/ca.crt \
#                            --cert   /etc/etcd/client.crt \
#                            --key    /etc/etcd/client.key
# =============================================================================
set -euo pipefail

ETCD_KEY="jxt/common/wvp-signing"
ENDPOINTS="127.0.0.1:2379"
DOCKER_CONTAINER=""
IF_ABSENT=0
DRY_RUN=0
ETCD_USER=""
ETCD_PASS=""
EXPIRES_MIN=30
TLS_FLAGS=()

usage() {
  sed -n '2,40p' "$0"
  exit "${1:-0}"
}

while [[ $# -gt 0 ]]; do
  case $1 in
    --endpoints)            ENDPOINTS="$2"; shift 2 ;;
    --key)                  ETCD_KEY="$2"; shift 2 ;;
    --docker)               DOCKER_CONTAINER="$2"; shift 2 ;;
    --if-absent)            IF_ABSENT=1; shift ;;
    --dry-run)              DRY_RUN=1; shift ;;
    --user)                 ETCD_USER="$2"; shift 2 ;;
    --pass)                 ETCD_PASS="$2"; shift 2 ;;
    --expires-min)          EXPIRES_MIN="$2"; shift 2 ;;
    --cacert|--cert|--key)  TLS_FLAGS+=("$1" "$2"); shift 2 ;;
    -h|--help)              usage 0 ;;
    *) echo "未知参数: $1"; echo; usage 1 ;;
  esac
done

# ---------- 组装 etcdctl 公共参数 ----------
ETCD_FLAGS=(--endpoints="$ENDPOINTS")
if [[ -n "$ETCD_USER" ]]; then
  if [[ -z "$ETCD_PASS" ]]; then echo "错误: --user 需配合 --pass"; exit 1; fi
  ETCD_FLAGS+=(--user="${ETCD_USER}:${ETCD_PASS}")
fi
if [[ ${#TLS_FLAGS[@]} -gt 0 ]]; then
  ETCD_FLAGS+=("${TLS_FLAGS[@]}")
fi

# 在容器内执行 etcdctl(--docker)或直接用宿主机的 etcdctl
run_etcdctl() {
  if [[ -n "$DOCKER_CONTAINER" ]]; then
    docker exec "$DOCKER_CONTAINER" etcdctl "${ETCD_FLAGS[@]}" "$@"
  else
    etcdctl "${ETCD_FLAGS[@]}" "$@"
  fi
}

# ---------- 前置检查 ----------
if [[ -z "$DOCKER_CONTAINER" ]] && ! command -v etcdctl >/dev/null 2>&1; then
  echo "错误: 宿主机未安装 etcdctl。"
  echo "可选: 安装 etcdctl,或用 --docker <容器名> 在容器内执行(如 --docker jxt-etcd)。"
  exit 1
fi

echo "检查 etcd 连通性: $ENDPOINTS ..."
if ! run_etcdctl endpoint health >/dev/null 2>&1; then
  echo "错误: 无法连接 etcd ($ENDPOINTS)。"
  echo "提示: 检查地址/端口/鉴权;若从宿主机运行,确保 2379 已暴露或用 --docker <容器名>。"
  exit 1
fi
echo "etcd 连通正常。"
echo

# ---------- 幂等检查 ----------
if [[ $IF_ABSENT -eq 1 ]]; then
  EXISTING="$(run_etcdctl get "$ETCD_KEY" --print-value-only 2>/dev/null || true)"
  if [[ -n "$EXISTING" ]]; then
    echo "[跳过] key 已存在(--if-absent),不覆盖: $ETCD_KEY"
    echo "现有值(已脱敏): $(echo "$EXISTING" | sed -E 's/("(appKey|appSecret|sm4Key)":"[^"]{8})[^"]+/\1********/g')"
    echo "如需轮换,去掉 --if-absent 重新运行(两端会自动热加载)。"
    exit 0
  fi
fi

# ---------- 生成密钥 ----------
APP_KEY="$(openssl rand -hex 16)"
APP_SECRET="$(openssl rand -hex 32)"
SM4_KEY="$(openssl rand -hex 16)"
JSON="$(printf '{"appKey":"%s","appSecret":"%s","sm4Key":"%s","expiresMin":%s}' \
  "$APP_KEY" "$APP_SECRET" "$SM4_KEY" "$EXPIRES_MIN")"

echo "已生成新密钥:"
echo "  appKey     = $APP_KEY  (32 hex, 客户端标识, 明文出现在请求 URL)"
echo "  appSecret  = $APP_SECRET  (64 hex, SM3 签名密钥, 切勿外泄)"
echo "  sm4Key     = $SM4_KEY  (32 hex, SM4-ECB 密钥, 切勿外泄)"
echo "  expiresMin = $EXPIRES_MIN  (时间戳/token 过期窗口, 分钟)"
echo
echo "将写入 etcd:  key=$ETCD_KEY   endpoints=$ENDPOINTS"
echo

if [[ $DRY_RUN -eq 1 ]]; then
  echo "[干跑] 未写入。JSON 值:"
  echo "$JSON"
  echo
  echo "⚠ 干跑模式下两端不会更新。去掉 --dry-run 实际写入。"
  exit 0
fi

# ---------- 写入 ----------
run_etcdctl put "$ETCD_KEY" "$JSON" >/dev/null
echo "写入完成。"

# ---------- 校验 ----------
CHECK="$(run_etcdctl get "$ETCD_KEY" --print-value-only 2>/dev/null || true)"
if [[ "$CHECK" == "$JSON" ]]; then
  echo "[OK] 写入校验通过,值与生成一致。"
else
  echo "[FAIL] 写入校验失败,读回值与生成不一致:"
  echo "  期望: $JSON"
  echo "  实际: $CHECK"
  exit 1
fi

echo
echo "================================ 下一步 ==================================="
echo "1) 安全: 把 appSecret / sm4Key 记录到密码保险箱(Vault 等),切勿提交到 git。"
echo "2) 热加载: WVP 与 security-management 通过 Watch 自动生效(约 1 秒内),无需重启。"
echo "   验证日志:"
echo "     WVP(Java):     grep 'SY签名配置' docker logs docker-polaris-wvp-1"
echo "     Security(Go):  grep 'WVP签名' docker logs security-management-security-management-1"
echo "3) adminToken: 由 WVP 启动时本地随机生成(不在 etcd),默认禁用管理绕过。"
echo "   详见 docs/maintenance/wvp-signing-keys.md"
echo "=========================================================================="
