#!/usr/bin/env bash
# =============================================================================
# 一键生成 release keystore 并创建 keystore.properties
#
# 用法：
#   ./generate-keystore.sh
#   ./generate-keystore.sh release.keystore mihoyo   # 自定义文件名/别名
#
# 依赖：JDK 的 keytool（随 Android Studio / JDK 安装）。
# 生成后即可执行：./gradlew :app:assembleRelease
#
# 💡 提示：CI 环境推荐使用环境变量方式传入签名密钥，无需此脚本。
#    详见 keystore.properties.template 中的说明。
# =============================================================================
set -euo pipefail

KEYSTORE_FILE="${1:-release.keystore}"
KEY_ALIAS="${2:-mihoyo}"
VALIDITY_DAYS=10000
KEYSIZE=2048

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

if ! command -v keytool >/dev/null 2>&1; then
    echo "❌ 找不到 keytool，请先安装 JDK（或把 JDK/bin 加入 PATH）。"
    exit 1
fi

if [ -f "$KEYSTORE_FILE" ]; then
    echo "⚠️  $KEYSTORE_FILE 已存在，未覆盖。如需重建请先手动删除。"
else
    echo "🔐 正在生成 keystore：$KEYSTORE_FILE （别名：$KEY_ALIAS）"
    read -r -s -p "请输入 keystore 口令: " STORE_PW; echo
    read -r -s -p "请再次确认口令: " STORE_PW2; echo
    if [ "$STORE_PW" != "$STORE_PW2" ]; then
        echo "❌ 两次输入不一致。"; exit 1
    fi

    keytool -genkeypair -v \
        -keystore "$KEYSTORE_FILE" \
        -alias "$KEY_ALIAS" \
        -keyalg RSA -keysize "$KEYSIZE" -validity "$VALIDITY_DAYS" \
        -storepass "$STORE_PW" -keypass "$STORE_PW" \
        -dname "CN=QuestTick, OU=Dev, O=Personal, L=, ST=, C=CN"

    echo "✅ keystore 生成完成。"

    # 生成 keystore.properties（本地开发用）
    if [ ! -f keystore.properties ]; then
        cat > keystore.properties <<EOF
storeFile=$KEYSTORE_FILE
storePassword=$STORE_PW
keyAlias=$KEY_ALIAS
keyPassword=$STORE_PW
EOF
        echo "✅ keystore.properties 已自动生成。"
    else
        echo "ℹ️  keystore.properties 已存在，未覆盖。"
    fi

    # 输出 base64 便于配置 CI Secret
    echo ""
    echo "═══════════════════════════════════════════════════════════════"
    echo "📋 CI 配置提示（GitHub Actions 环境变量方式）："
    echo ""
    echo "  将以下 Base64 字符串设为 GitHub Secret KEYSTORE_BASE64 ："
    echo ""
    base64 -w0 "$KEYSTORE_FILE"
    echo ""
    echo ""
    echo "  然后在 Settings → Secrets 中添加："
    echo "    KEYSTORE_BASE64   = （上面的 base64 字符串）"
    echo "    KEYSTORE_PASSWORD = $STORE_PW"
    echo "    KEY_ALIAS         = $KEY_ALIAS"
    echo "    KEY_PASSWORD      = $STORE_PW"
    echo ""
    echo "  CI 中 Gradle 将自动通过环境变量读取签名配置，无需 keystore.properties 文件。"
    echo "═══════════════════════════════════════════════════════════════"
fi

echo ""
echo "🚀 现在可以构建已签名 Release APK："
echo ""
echo "  方式 1（本地，使用 keystore.properties）："
echo "    ./gradlew :app:assembleRelease"
echo ""
echo "  方式 2（环境变量，适用于 CI 或临时使用）："
echo "    KEYSTORE_FILE=$KEYSTORE_FILE \\"
echo "    KEYSTORE_PASSWORD=<口令> \\"
echo "    KEY_ALIAS=$KEY_ALIAS \\"
echo "    KEY_PASSWORD=<口令> \\"
echo "    ./gradlew :app:assembleRelease"
