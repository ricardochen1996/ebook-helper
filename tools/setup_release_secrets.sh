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
REPO="${REPO:-ricardochen1996/elink-reader-helper}"

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

echo "正在写入 GitHub Secrets 到 ${REPO}（值不会打印）..."
base64 < "$KEYSTORE" | tr -d '\n' | gh secret set ANDROID_KEYSTORE_BASE64 --repo "$REPO"
printf '%s' "$STORE_PASSWORD" | gh secret set ANDROID_KEYSTORE_PASSWORD --repo "$REPO"
printf '%s' "$KEY_ALIAS" | gh secret set ANDROID_KEY_ALIAS --repo "$REPO"
printf '%s' "$KEY_PASSWORD" | gh secret set ANDROID_KEY_PASSWORD --repo "$REPO"

echo "完成。"
echo "接下来: GitHub → Actions → Android Release Build → Run workflow"
echo "或: gh workflow run android-release.yml --repo ${REPO}"
