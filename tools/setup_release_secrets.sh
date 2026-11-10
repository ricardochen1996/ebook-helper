#!/usr/bin/env bash
set -euo pipefail

# 把 release keystore 和 keystore.properties 写入 GitHub Actions Secrets。
# 用法:
#   tools/setup_release_secrets.sh /path/to/readask-release.jks /path/to/keystore.properties
# 可用 REPO=owner/repo 覆盖默认仓库。

if [ "$#" -ne 2 ]; then
    echo "用法: $0 <readask-release.jks> <keystore.properties>"
    exit 1
fi

KEYSTORE="$1"
PROPS="$2"
REPO="${REPO:-ricardochen1996/ebook-helper}"

for f in "$KEYSTORE" "$PROPS"; do
    if [ ! -f "$f" ]; then
        echo "找不到文件: $f"
        exit 1
    fi
done

if ! command -v gh >/dev/null 2>&1; then
    echo "未安装 GitHub CLI (gh)。macOS 可运行: brew install gh && gh auth login"
    exit 1
fi
if ! gh auth status >/dev/null 2>&1; then
    echo "GitHub CLI 未登录。请先运行: gh auth login"
    exit 1
fi

read_prop() {
    local key="$1"
    sed -n "s/^[[:space:]]*${key}[[:space:]]*=[[:space:]]*//p" "$PROPS" | head -n 1
}

STORE_PASSWORD="$(read_prop storePassword)"
KEY_ALIAS="$(read_prop keyAlias)"
KEY_PASSWORD="$(read_prop keyPassword)"

for pair in "STORE_PASSWORD:$STORE_PASSWORD" "KEY_ALIAS:$KEY_ALIAS" "KEY_PASSWORD:$KEY_PASSWORD"; do
    name="${pair%%:*}"
    value="${pair#*:}"
    if [ -z "$value" ]; then
        echo "keystore.properties 缺少 ${name}"
        exit 1
    fi
done

# 已发布 v1.0.0 的签名证书。用旧 key 覆盖 Secrets 会让已安装用户无法覆盖更新。
EXPECTED_CERT="${EXPECTED_CERT:-1b13719e3ebc5e4e86f08a69250681d7f52cefbea19cef928d22616f9adec8ed}"
if command -v keytool >/dev/null 2>&1 && command -v openssl >/dev/null 2>&1; then
    CERT="$(keytool -exportcert -keystore "$KEYSTORE" -storepass "$STORE_PASSWORD" \
        -alias "$KEY_ALIAS" -rfc 2>/dev/null \
        | openssl x509 -fingerprint -sha256 -noout 2>/dev/null \
        | sed 's/^.*=//' | tr -d ':' | tr 'A-F' 'a-f' || true)"
    if [ -n "$CERT" ] && [ "$CERT" != "$EXPECTED_CERT" ]; then
        echo "错误: keystore 证书 SHA-256 与已发布版本不一致。"
        echo "  当前: $CERT"
        echo "  期望: $EXPECTED_CERT"
        echo "用它覆盖 CI Secrets 会让已安装用户无法覆盖更新（只能卸载重装并丢失会话）。"
        echo "如确认要更换签名，设置 EXPECTED_CERT=$CERT 后重试。"
        exit 1
    fi
    if [ -z "$CERT" ]; then
        echo "警告: 无法读取 keystore 证书指纹，跳过签名一致性校验。"
    fi
fi

echo "正在写入 GitHub Secrets 到 ${REPO}（值不会打印）..."
base64 < "$KEYSTORE" | tr -d '\n' | gh secret set ANDROID_KEYSTORE_BASE64 --repo "$REPO"
printf '%s' "$STORE_PASSWORD" | gh secret set ANDROID_KEYSTORE_PASSWORD --repo "$REPO"
printf '%s' "$KEY_ALIAS" | gh secret set ANDROID_KEY_ALIAS --repo "$REPO"
printf '%s' "$KEY_PASSWORD" | gh secret set ANDROID_KEY_PASSWORD --repo "$REPO"

echo "完成。"
echo "接下来: GitHub → Actions → Android Release Build → Run workflow"
echo "或: gh workflow run android-release.yml --repo ${REPO}"
