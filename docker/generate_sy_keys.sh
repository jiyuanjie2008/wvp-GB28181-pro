#!/bin/bash
# 生成 WVP sy.enable 集成所需的密钥
# 用法: bash generate_sy_keys.sh

SM4_KEY=$(openssl rand -hex 16)
APP_KEY=$(openssl rand -hex 16)
APP_SECRET=$(openssl rand -hex 32)
ADMIN_TOKEN=$(openssl rand -hex 32)

echo "SM4_KEY: $SM4_KEY"
echo "APP_KEY: $APP_KEY"
echo "APP_SECRET: $APP_SECRET"
echo "ADMIN_TOKEN: $ADMIN_TOKEN"
