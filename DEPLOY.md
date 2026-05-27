# 梦柒兮 部署指南

基于您的 **宝塔 Linux 面板 + SSL 证书 + `47.110.65.93`** 环境定制。

---

## 一、服务器部署状态 ✅（已完成）

| 组件 | 状态 | 说明 |
|------|------|------|
| Node.js | ✅ v16.20.2 | `/usr/local/nodejs/bin/node` |
| PM2 | ✅ v7.0.1 | 已配置开机自启 |
| Nginx | ✅ v1.26.1 | WebSocket 反向代理 |
| 信令服务 | ✅ 运行中 | 端口 9527 (内部), PM2 进程名: `crossnotify` |
| WSS 端点 | ✅ 可用 | `wss://47.110.65.93:9528/ws?type=pc` |
| FCM 推送 | ❌ 未配置 | 需上传 Firebase 私钥 |

### 已完成的部署操作

```bash
# 项目目录
/www/wwwroot/crossnotify-server/

# 服务管理命令
pm2 status crossnotify        # 查看状态
pm2 logs crossnotify          # 查看日志
pm2 restart crossnotify       # 重启服务
pm2 stop crossnotify          # 停止服务
```

### Nginx 配置

WebSocket 反向代理端口 **9528** (SSL)，使用宝塔面板的已有证书自动配置。

---

## 二、开启 FCM 推送（可选但推荐）

1. 在 [Firebase Console](https://console.firebase.google.com) 创建项目
2. 项目设置 → **服务帐号** → "生成新的私钥"，下载 JSON 文件
3. 上传至服务器:
   ```bash
   # 将 firebase-key.json 上传到服务器后执行
   # 编辑 .env 文件确认路径正确
   ```
4. 重启服务: `pm2 restart crossnotify`

---

## 三、PC 客户端（Electron）

### 3.1 开发运行

```bash
cd pc-client
npm install
npm start
```

### 3.2 打包分发

```bash
npm run dist
```

打包后的 exe 安装包在 `pc-client/dist/` 目录下。

---

## 四、Android 客户端

### 4.1 导入项目

1. 用 Android Studio 打开 `android-client/` 目录
2. 等待 Gradle 同步完成

### 4.2 配置 Firebase

1. 在 Firebase Console 中，为 Android 应用添加 FCM（包名: `com.crossnotify`）
2. 下载 `google-services.json` 放入 `android-client/app/` 目录

### 4.3 构建 APK

```bash
cd android-client
./gradlew assembleDebug
```

---

## 五、连接信息

| 端 | WebSocket 地址 |
|----|---------------|
| PC 端 | `wss://47.110.65.93:9528/ws?type=pc` |
| Android | `wss://47.110.65.93:9528/ws?type=android` |

---

## 六、常见问题

### WebSocket 连接失败 (ERR\_SSL\_VERSION\_OR\_CIPHER\_MISMATCH)
- 确认使用 `wss://47.110.65.93:9528/ws?type=pc` 地址
- 检查防火墙是否放行了 9528 端口

### Android 收不到 FCM 推送
- 确认 Firebase 私钥 JSON 已上传至 `/www/wwwroot/crossnotify-server/`
- 检查 Android 端通知权限是否开启
- FCM 仅在 WebSocket 断连时作为备用通道

### 服务断开后自动重连
- PC 客户端每 3 秒自动重连
- Android 端 WebSocketService 每 5 秒自动重连
- FCM 作为备用通道在 WebSocket 断连时生效
