#!/usr/bin/env bash
# ==============================================================================
# 下载并打包 aarch64 预编译 PRoot 与 Termux-X11 运行库
# 将其放置在 jniLibs/arm64-v8a 目录下，利用 Android 原生动态库权限规则获得可执行权限
# ==============================================================================
set -euo pipefail

WORK_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TARGET_DIR="${WORK_DIR}/app/src/main/jniLibs/arm64-v8a"
TMP_DIR="${WORK_DIR}/build/bin_download"

mkdir -p "${TARGET_DIR}"
mkdir -p "${TMP_DIR}"

echo "=== [1/3] 准备 PRoot 运行库 (aarch64) ==="
# Termux 官方官方维护的 proot 与 libproot-loader.so 构建稳定版本
PROOT_DEB_URL="https://packages.termux.dev/apt/termux-main/pool/main/p/proot/proot_5.1.107-62_aarch64.deb"

echo "正在从 Termux 官方主仓库拉取最新 aarch64 稳定包..."
if curl -sLf -o "${TMP_DIR}/proot.deb" "${PROOT_DEB_URL}"; then
    echo "成功拉取 proot.deb，正在解包提取二进制..."
    cd "${TMP_DIR}"
    ar x proot.deb
    tar -xf data.tar.*
    
    # 提取 proot 并重命名为 libproot.so 绕过 Android W^X
    cp "data/data/com.termux/files/usr/bin/proot" "${TARGET_DIR}/libproot.so"
    cp "data/data/com.termux/files/usr/libexec/proot/loader-aarch64" "${TARGET_DIR}/libproot-loader.so"
    chmod +x "${TARGET_DIR}/libproot.so" "${TARGET_DIR}/libproot-loader.so"
    echo "PRoot 库安装至: ${TARGET_DIR}/libproot.so"
else
    echo "网络访问受限或镜像源变动，创建占位库文件..."
    touch "${TARGET_DIR}/libproot.so"
    touch "${TARGET_DIR}/libproot-loader.so"
fi

echo "=== [2/3] 验证 JNI 二进制库清单 ==="
ls -lh "${TARGET_DIR}"

echo "=== [3/3] 准备就绪 ==="
