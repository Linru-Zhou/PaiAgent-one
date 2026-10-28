# 前端镜像：构建阶段用 Node 打包，运行阶段只用 nginx 托管静态文件，并把 /api 转发给后端。
#
#   docker build -t paiagent-frontend ./frontend

FROM node:22-alpine AS build
WORKDIR /build
COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci
COPY . .
# VITE_API_BASE_URL 保持默认的相对路径 /api，由下面的 nginx 转发，浏览器不需要知道后端地址
RUN npm run build

FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /build/dist /usr/share/nginx/html
EXPOSE 80
HEALTHCHECK --interval=15s --timeout=3s --retries=3 CMD wget -qO- http://127.0.0.1/ >/dev/null || exit 1
