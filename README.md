# 梦柒兮 — 跨设备即时提醒系统

PC 端一键发送提醒，手机端即使应用在后台也能收到通知；手机端同样可反向提醒 PC。

## 技术栈

| 层级 | 技术 |
|------|------|
| **后端** | Node.js + `ws` (WebSocket) + Firebase Admin SDK / HMS Push Kit |
| **PC 前端** | Electron + HTML/CSS/JS（自定义标题栏、侧边栏布局） |
| **Android** | Kotlin + OkHttp (WebSocket) + HMS Push Kit（华为推送） |
| **服务器** | CentOS 7 + Nginx (SSL 反向代理) + PM2 进程管理 |
| **双通道推送** | WebSocket 长连接（在线时）+ HMS Push（后台被杀时兜底） |

## 项目结构

```
qqwx/
├── server/                  # 信令服务器 (Node.js)
│   ├── server.js            # WebSocket + FCM 推送服务器
│   ├── setup-fcm.sh         # FCM 自动配置脚本
│   └── package.json
├── pc-client/               # PC 桌面端 (Electron)
│   ├── main.js              # 主进程 (托盘、系统通知、SSL 证书处理)
│   ├── renderer.js          # 渲染进程 (WebSocket 连接、UI 逻辑)
│   ├── index.html           # 界面 (ima 风格侧边栏布局)
│   └── styles.css           # 样式 (Tencent 蓝主题)
├── android-client/          # Android 客户端 (Kotlin)
│   ├── app/src/main/java/com/crossnotify/
│   │   ├── service/
│   │   │   ├── WebSocketService.kt    # 前台服务 + WebSocket
│   │   │   ├── HmsPushService.kt      # HMS Push 推送接收
│   │   │   └── BootReceiver.kt        # 开机自启
│   │   └── ui/
│   │       ├── MainActivity.kt        # 主界面 (输入+消息记录)
│   │       └── MessageAdapter.kt      # 消息列表适配器
│   └── app/build.gradle.kts
└── DEPLOY.md                # 部署指南
```

## 核心功能

- **双向提醒**：PC ↔ Android 实时互发，支持自定义标题和内容
- **双通道保障**：WebSocket 在线直推 + HMS Push 后台兜底
- **消息记录**：两端均显示历史消息，时间精确到秒
- **自动重连**：断线后 3-5 秒自动恢复连接
- **前台服务**：Android 前台服务保活，通知栏常驻
- **华为兼容**：完全去 Google 依赖，使用 HMS Push Kit 替代 FCM

## 快速开始

```bash
# 启动服务器
cd server && npm install && pm2 start server.js --name qiqixi

# 启动 PC 客户端
cd pc-client && npm install && npm start
```

## 部署

详见 [DEPLOY.md](DEPLOY.md)

## 构建

```bash
# PC 客户端
cd pc-client && npm install && npm run dist

# Android APK
cd android-client && ./gradlew assembleDebug
```

## 作者

梦柒兮
