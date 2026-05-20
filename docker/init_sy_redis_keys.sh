#!/bin/bash
# 将 SM3+SM4 签名凭证写入 WVP 使用的 Redis
# 用法: bash init_sy_redis_keys.sh [--redis-host HOST] [--redis-port PORT] [--redis-password PWD] [--redis-db DB]
#
# 默认值取自 docker-compose.yml:
#   host=jxt-redis, port=6379, password=jxt-redis-2026, db=0
#
# 执行后会输出 settings.yml 配置片段，复制到 security-management 即可

set -euo pipefail

# ---- 默认值 ----
REDIS_HOST="jxt-redis"
REDIS_PORT="6379"
REDIS_PASSWORD="jxt-redis-2026"
REDIS_DB="1"

# ---- 解析参数 ----
while [[ $# -gt 0 ]]; do
  case $1 in
    --redis-host)     REDIS_HOST="$2";     shift 2 ;;
    --redis-port)     REDIS_PORT="$2";     shift 2 ;;
    --redis-password) REDIS_PASSWORD="$2"; shift 2 ;;
    --redis-db)       REDIS_DB="$2";       shift 2 ;;
    *)
      echo "未知参数: $1"
      echo "用法: $0 [--redis-host HOST] [--redis-port PORT] [--redis-password PWD] [--redis-db DB]"
      exit 1
      ;;
  esac
done

# ---- 生成密钥 ----
SM4_KEY=$(openssl rand -hex 16)
APP_KEY=$(openssl rand -hex 16)
APP_SECRET=$(openssl rand -hex 32)
ADMIN_TOKEN=$(openssl rand -hex 32)

# ---- Redis CLI 命令前缀 ----
REDIS_CMD="redis-cli -h $REDIS_HOST -p $REDIS_PORT -a $REDIS_PASSWORD -n $REDIS_DB --no-auth-warning"

# ---- 检查 Redis 连接 ----
echo "检查 Redis 连接: $REDIS_HOST:$REDIS_PORT (db=$REDIS_DB) ..."
if ! $REDIS_CMD PING > /dev/null 2>&1; then
  echo "错误: 无法连接到 Redis ($REDIS_HOST:$REDIS_PORT)"
  echo "提示: 如果从宿主机运行，请确保 Redis 端口已暴露或使用正确的 host"
  exit 1
fi
echo "Redis 连接成功"
echo ""

# ---- 写入凭证 ----
echo "写入 SM3+SM4 签名凭证到 Redis ..."
echo "  SYSTEM_SM4_KEY           = $SM4_KEY"
echo "  SYSTEM_APPKEY            = {appKey: $APP_KEY, appSecret: ***}"
echo "  sys_INTERFACE_VALID_TIME = {systemValue: 30}"
echo "  SYSTEM_ACCESS_TOKEN      = ***"
echo ""

$REDIS_CMD SET SYSTEM_SM4_KEY "$SM4_KEY"
$REDIS_CMD SET SYSTEM_APPKEY "{\"appKey\":\"$APP_KEY\",\"appSecret\":\"$APP_SECRET\"}"
$REDIS_CMD SET sys_INTERFACE_VALID_TIME '{"systemValue":30}'
$REDIS_CMD SET SYSTEM_ACCESS_TOKEN "$ADMIN_TOKEN"

# ---- 验证 ----
echo "验证写入结果 ..."
echo ""

SM4_CHECK=$($REDIS_CMD GET SYSTEM_SM4_KEY)
APPKEY_CHECK=$($REDIS_CMD GET SYSTEM_APPKEY)
TIME_CHECK=$($REDIS_CMD GET sys_INTERFACE_VALID_TIME)
TOKEN_CHECK=$($REDIS_CMD GET SYSTEM_ACCESS_TOKEN)

FAIL=0

if [ "$SM4_CHECK" = "$SM4_KEY" ]; then
  echo "  [OK] SYSTEM_SM4_KEY"
else
  echo "  [FAIL] SYSTEM_SM4_KEY: 期望 $SM4_KEY, 实际 $SM4_CHECK"
  FAIL=$((FAIL+1))
fi

if echo "$APPKEY_CHECK" | grep -q "$APP_KEY" && echo "$APPKEY_CHECK" | grep -q "$APP_SECRET"; then
  echo "  [OK] SYSTEM_APPKEY"
else
  echo "  [FAIL] SYSTEM_APPKEY: $APPKEY_CHECK"
  FAIL=$((FAIL+1))
fi

if echo "$TIME_CHECK" | grep -q "30"; then
  echo "  [OK] sys_INTERFACE_VALID_TIME ($TIME_CHECK)"
else
  echo "  [FAIL] sys_INTERFACE_VALID_TIME: $TIME_CHECK"
  FAIL=$((FAIL+1))
fi

if [ -n "$TOKEN_CHECK" ]; then
  echo "  [OK] SYSTEM_ACCESS_TOKEN"
else
  echo "  [FAIL] SYSTEM_ACCESS_TOKEN: 为空"
  FAIL=$((FAIL+1))
fi

# ---- 输出配置 ----
echo ""
if [ $FAIL -eq 0 ]; then
  echo "============================================"
  echo "  全部写入成功"
  echo "============================================"
  echo ""
  echo "将以下内容复制到 security-management/config/settings.yml:"
  echo ""
  echo "# WVP SM3+SM4 signing configuration"
  echo "wvp:"
  echo "  appKey: \"$APP_KEY\""
  echo "  appSecret: \"$APP_SECRET\""
  echo "  sm4Key: \"$SM4_KEY\""
  echo "  expiresMin: 30"
else
  echo "写入完成，但有 $FAIL 个失败"
  exit 1
fi
