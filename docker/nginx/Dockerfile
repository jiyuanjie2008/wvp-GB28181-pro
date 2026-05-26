# -------------------------------
# 第一阶段：前端构建
# -------------------------------
FROM swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/node:lts-alpine AS builder

# 1. 设置工作目录
WORKDIR /build

# 2. 复制源码
COPY ./web .

# 3. 配置淘宝源并安装依赖、构建
# 使用 .npmrc 配置源通常比命令行参数更稳定
RUN echo "registry=https://registry.npmmirror.com" > .npmrc && \
    npm install && \
    npm run build:prod

# 可选：打印构建结果，确认文件生成位置
RUN echo "=== 构建产物列表 ===" && ls -la /src/main/resources/static

# 4. 复制运行时配置（config.js 设置后端 API 地址）
COPY ./docker/nginx/config.js /src/main/resources/static/static/js/config.js


# -------------------------------
# 第二阶段：Nginx 运行时
# -------------------------------
FROM swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/nginx:alpine

# 设置时区
ARG TZ=Asia/Shanghai

# 5. 从构建阶段复制前端文件（匹配 nginx.conf.template 的 root /opt/dist）
COPY --from=builder /src/main/resources/static /opt/dist

# 5. 清理默认配置（如果需要自定义配置请在此处 ADD 或 COPY 你的 nginx.conf）
RUN rm -f /etc/nginx/conf.d/default.conf

# 6. 暴露端口
EXPOSE 8080

# 7. 启动 Nginx
CMD ["nginx", "-g", "daemon off;"]