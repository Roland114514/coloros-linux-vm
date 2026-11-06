#!/usr/bin/env bash
# ==============================================================================
# 下载并打包 aarch64 预编译 PRoot 与 loader 运行库
# 将其放置在 jniLibs/arm64-v8a 目录下，利用 Android 原生动态库权限规则获得可执行权限
# ==============================================================================
set -euo pipefail

WORK_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TARGET_DIR="${WORK_DIR}/app/src/main/jniLibs/arm64-v8a"
TMP_DIR="${WORK_DIR}/build/bin_download"

mkdir -p "${TARGET_DIR}"
mkdir -p "${TMP_DIR}"

echo "=== [1/3] 准备 PRoot 运行库 (aarch64) ==="
PROOT_DEB_URL="https://grimler.se/termux/termux-main/pool/main/p/proot/proot_5.1.107.102_aarch64.deb"

echo "正在从稳定仓库拉取最新 aarch64 deb 包..."
if curl -sLf -o "${TMP_DIR}/proot.deb" "${PROOT_DEB_URL}"; then
    echo "成功拉取 proot.deb，正在解包提取二进制..."
    python3 -c '
with open("'"${TMP_DIR}"'/proot.deb", "rb") as f:
    magic = f.read(8)
    assert magic == b"!<arch>\n"
    while True:
        header = f.read(60)
        if len(header) < 60: break
        name = header[:16].decode("ascii", errors="ignore").strip().rstrip("/")
        size = int(header[48:58].decode("ascii").strip())
        content = f.read(size)
        if size % 2 == 1: f.read(1)
        if "data.tar" in name:
            with open("'"${TMP_DIR}"'/" + name, "wb") as out:
                out.write(content)
'
    tar -xf "${TMP_DIR}"/data.tar.* -C "${TMP_DIR}"
    
    cp "${TMP_DIR}/data/data/com.termux/files/usr/bin/proot" "${TARGET_DIR}/libproot.so"
    cp "${TMP_DIR}/data/data/com.termux/files/usr/libexec/proot/loader" "${TARGET_DIR}/libproot-loader.so"
    chmod +x "${TARGET_DIR}/libproot.so" "${TARGET_DIR}/libproot-loader.so"
    echo "PRoot 库安装至: ${TARGET_DIR}/libproot.so"
else
    echo "网络受限，保留既有二进制文件..."
fi

echo "=== [2/3] 验证 JNI 二进制库清单 ==="
ls -lh "${TARGET_DIR}"

echo "=== [3/3] 准备就绪 ==="
