<div align="center">
  <h1>梦柒兮 · CrossNotify</h1>
  <p><strong>跨设备即时提醒系统</strong> — PC ↔ Android 实时互传，永不离线</p>
  <p>
    <a href="#-快速开始">快速开始</a> •
    <a href="#-核心功能">功能</a> •
    <a href="#-项目结构">结构</a> •
    <a href="#-构建">构建</a> •
    <a href="#-部署">部署</a>
  </p>
  <p>
    <img src="https://img.shields.io/badge/Electron-30.5-47848F?logo=electron">
    <img src="https://img.shields.io/badge/Android-26+-3DDC84?logo=android">
    <img src="https://img.shields.io/badge/Kotlin-1.8-7F52FF?logo=kotlin">
    <img src="https://img.shields.io/badge/Node.js-16-339933?logo=nodedotjs">
    <img src="https://img.shields.io/badge/HMS_Push-6.11-FF0000?logo=huawei">
  </p>
</div>

---

## 📖 简介

**梦柒兮** 是一款轻量级跨设备即时提醒工具。在 PC 上输入内容，一键推送到 Android 手机；手机也能反向发消息和照片到 PC。

采用 **WebSocket 长连接 + 华为 HMS Push 双通道** 架构，即使手机在后台或被杀进程，也能通过系统推送收到消息。

### 适用场景

- 💼 办公时手机静音，PC 发提醒不怕错过
- 🏠 家人在 PC 上提醒手机端的你
- 📸 手机拍照即时发到 PC 保存
- 📌 重要事项置顶提醒，6 小时内不消失

---

## ✨ 核心功能

| 功能 | PC 端 | Android 端 | 悬浮窗 |
|------|:-----:|:----------:|:------:|
| 双向文字提醒 | ✅ | ✅ | ✅ |
| 照片发送/预览/保存 | ✅ | ✅ | ✅ |
| **📌 置顶消息**（1h/6h/24h） | ✅ | ✅ | ✅ |
| **消息历史持久化**（重启不丢） | ✅ | ✅ | ✅ |
| **悬浮窗消息历史** | - | - | ✅（最近 20 条） |
| 双通道推送（WS + HMS） | - | ✅ | - |
| 后台保活（WakeLock） | - | ✅ | - |
| 连接状态指示 | ✅ | ✅ | ✅ |
| 自动重连 | ✅（3s） | ✅（5s） | ✅（5s） |
| 服务端 HMS/FCM 离线推送 | ✅ | ✅ | - |
| 华为设备电池优化引导 | - | ✅ | - |
| 开机自启 | - | ✅ | - |

---

## 🏗 系统架构

```
┌─────────────┐     WebSocket      ┌──────────────┐     WebSocket      ┌─────────────┐
│  PC 客户端   │ ◄──────────────► │   服务器      │ ◄──────────────► │  Android     │
│  (Electron)  │    wss://9528     │  Node.js      │    ws://9527      │  (Kotlin)    │
└─────────────┘                   │  Port 9527    │                   └──────┬──────┘
                                   │               │                          │
                                   │  ┌─────────┐  │                   ┌──────┴──────┐
                                   │  │  HMS    │  │                   │  HMS Push    │
                                   │  │  Push   │◄─┼───────────────────┤  Kit (华为)  │
                                   │  └─────────┘  │  离线推送兜底      └─────────────┘
                                   │  ┌─────────┐  │
                                   │  │  FCM    │  │
                                   │  │(Google) │  │  (备用推送通道)
                                   │  └─────────┘  │
                                   └──────────────┘
                                        CentOS 7
                                     Nginx SSL 代理
                                      PM2 进程管理
```

---

## 🚀 快速开始

### 下载预构建版本

从 [GitHub Releases](https://github.com/mengqixi/qqwx/releases) 下载最新版：

| 平台 | 文件 | 说明 |
|------|------|------|
| Windows | `梦柒兮.exe` | 解压后双击运行 |
| Android | `梦柒兮.apk` | 直接安装到手机 |

### 从源码运行

```bash
# 1. 启动服务器（已有服务器可跳过）
cd server
npm install
cp .env.example .env    # 编辑配置
pm2 start server.js --name crossnotify

# 2. PC 客户端
cd pc-client
npm install
npm start

# 3. Android（用 Android Studio 打开）
cd android-client
# 用 Android Studio 打开此目录，Build → Build APK
```

### 连接信息

默认服务器地址已配置为 `47.110.65.93`，PC 客户端启动后会自动连接。

- **PC 端**: `wss://47.110.65.93:9528/ws?type=pc`
- **Android**: `ws://47.110.65.93:9527/?type=android`

---

## 🎯 如何使用

### 发送提醒
1. 在输入框输入内容
2. （可选）勾选 **📌 置顶** 并选择时长（1h / 6h / 24h）
3. 点击"发送提醒"或按 Enter
4. 手机端立即弹出通知

### 发送照片
点击「照片」按钮选择图片，自动发送到对方端。

### 置顶消息
- 置顶消息在所有端显示 **📌** 标记和 **剩余时间**
- 到期后自动取消置顶，变为普通消息
- 置顶消息始终显示在列表顶部

### 悬浮窗（Android）
1. 在 App 内连接后点击「悬浮窗」按钮
2. 授予悬浮窗权限
3. 悬浮窗可查看 **最近 20 条历史消息**
4. 点击历史消息可打开 App 查看完整记录

### 华为手机后台设置
为保证后台持续接收，请做以下设置：
```
设置 → 应用 → 应用管理 → 梦柒兮 → 电池 → 不允许限制
设置 → 应用 → 梦柒兮 → 权限 → 开启悬浮窗
设置 → 应用 → 梦柒兮 → 其他权限 → 自启动
```

---

## 📁 项目结构

```
qqwx/
├── dist/                          # 构建输出
│   ├── 梦柒兮.apk                 # Android 安装包
│   └── win-unpacked/
│       └── 梦柒兮.exe             # PC 可执行文件
│
├── server/                        # 信令服务器 (Node.js)
│   ├── server.js                  # WebSocket + HMS/FCM 推送
│   ├── .env.example               # 环境配置模板
│   └── package.json
│
├── pc-client/                     # PC 桌面端 (Electron)
│   ├── main.js                    # 主进程 + IPC
│   ├── preload.js                 # 上下文桥接
│   ├── renderer.js                # UI 逻辑 + WebSocket
│   ├── index.html                 # 界面
│   ├── styles.css                 # 毛玻璃主题
│   └── package.json
│
├── android-client/                # Android 客户端 (Kotlin)
│   ├── app/src/main/java/com/crossnotify/
│   │   ├── service/
│   │   │   ├── WebSocketService.kt    # 前台服务 + WebSocket + WakeLock
│   │   │   ├── HmsPushService.kt      # 华为推送接收
│   │   │   ├── BubbleService.kt       # 悬浮窗（含消息历史）
│   │   │   └── BootReceiver.kt        # 开机自启
│   │   ├── ui/
│   │   │   ├── MainActivity.kt        # 主界面（含置顶功能）
│   │   │   └── MessageAdapter.kt      # 消息列表适配器
│   │   └── storage/
│   │       └── MessageStorage.kt      # 消息本地持久化
│   └── app/src/main/res/layout/
│       ├── activity_main.xml          # 主界面布局
│       ├── bubble_overlay.xml         # 悬浮窗布局
│       └── message_item.xml           # 消息项布局
│
├── README.md
├── DEPLOY.md                       # 部署指南
└── .gitignore
```

---

## 🛠 构建

### PC 客户端

```bash
cd pc-client
npm install
npm run dist          # 打包 NSIS 安装包
npm run pack          # 仅打包目录（免安装）
npm start             # 开发模式运行
```

### Android APK

```bash
cd android-client
./gradlew assembleDebug
# APK 生成在: app/build/outputs/apk/debug/app-debug.apk
```

或者在 **Android Studio** 中打开 `android-client/` 目录，点击 `Build → Build APK(s)`。

---

## 🌐 部署

详见 [DEPLOY.md](DEPLOY.md)

### 服务器（已部署）

| 组件 | 状态 | 说明 |
|------|------|------|
| 信令服务 | ✅ 运行中 | PM2 进程: `crossnotify` |
| WebSocket 端口 | ✅ 9527 (内) / 9528 (SSL) | Nginx 反向代理 |
| HMS Push | ✅ 已配置 | 华为推送 |
| FCM Push | ✅ 已配置 | Google Firebase |

---

## 📦 技术栈

| 层级 | 技术 |
|------|------|
| **后端** | Node.js + ws + Firebase Admin SDK + HMS Push API |
| **PC 前端** | Electron 30 + Vanilla JS + CSS Glassmorphism |
| **Android** | Kotlin + OkHttp 4.12 + HMS Push Kit 6.11 + AndroidX |
| **服务器** | CentOS 7 + Nginx 1.26 + PM2 |
| **构建** | electron-builder + Gradle + AGP 7.4.2 |

---

## 📄 协议

本项目为个人作品，仅供学习参考。

---

<div align="center">
  <sub>Made with ❤️ by 梦柒兮</sub>
</div>
