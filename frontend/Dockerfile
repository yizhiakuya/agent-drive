FROM node:22-alpine AS build
WORKDIR /app
ARG http_proxy
ARG https_proxy
ENV http_proxy=$http_proxy https_proxy=$https_proxy
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:alpine
COPY --from=build /app/dist /usr/share/nginx/html
# API 反代到后端服务
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
