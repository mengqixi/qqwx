#!/bin/bash
# CrossNotify FCM 自动配置脚本
# 在服务器上运行: bash setup-fcm.sh <firebase-key.json路径>
# ================================================================

set -e

if [ $# -lt 1 ]; then
    echo "用法: bash setup-fcm.sh <path-to-firebase-key.json>"
    echo ""
    echo "请先在 Firebase Console (https://console.firebase.google.com) 中:"
    echo "  1. 创建项目 (或使用已有项目)"
    echo "  2. 项目设置 → 服务帐号 → 生成新的私钥"
    echo "  3. 将下载的 JSON 文件路径作为参数传入此脚本"
    exit 1
fi

KEY_FILE="$1"
PROJECT_DIR="/www/wwwroot/crossnotify-server"

if [ ! -f "$KEY_FILE" ]; then
    echo "错误: 文件 $KEY_FILE 不存在"
    exit 1
fi

echo "=========================================="
echo " CrossNotify FCM 配置"
echo "=========================================="
echo ""

# 1. 复制密钥文件到项目目录
echo "📦 复制 Firebase 密钥..."
cp "$KEY_FILE" "$PROJECT_DIR/firebase-key.json"
echo "   ✅ 已复制到 $PROJECT_DIR/firebase-key.json"

# 2. 更新 .env 配置
echo "📝 更新环境变量..."
cat > "$PROJECT_DIR/.env" << EOF
FCM_KEY_PATH=$PROJECT_DIR/firebase-key.json
WS_PORT=9527
EOF
echo "   ✅ .env 已更新"

# 3. 测试配置
echo "🔍 验证配置..."
cd "$PROJECT_DIR"
node -e "
require('dotenv').config();
try {
    const admin = require('firebase-admin');
    const serviceAccount = require(process.env.FCM_KEY_PATH);
    admin.initializeApp({ credential: admin.credential.cert(serviceAccount) });
    console.log('   ✅ Firebase Admin SDK 初始化成功');
    console.log('   📋 项目ID:', serviceAccount.project_id);
    console.log('   📋 客户端邮箱:', serviceAccount.client_email);
} catch(e) {
    console.log('   ❌ 初始化失败:', e.message);
    process.exit(1);
}
" 2>&1

# 4. 重启服务
echo ""
echo "🔄 重启 CrossNotify 服务..."
pm2 restart crossnotify

echo ""
echo "=========================================="
echo " ✅ FCM 配置完成！"
echo "=========================================="
echo ""
echo "下一步:"
echo "  在 Firebase Console → 项目设置 → 常规 → 添加 Android 应用"
echo "  包名: com.crossnotify"
echo "  下载 google-services.json 放入 android-client/app/ 目录"
echo "  然后在 Android Studio 中构建 APK"
