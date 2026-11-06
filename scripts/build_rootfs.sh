#!/usr/bin/env bash
# ==============================================================================
# OPPO 平板 ColorOS 17 - Ubuntu 24.04 (Noble) ARM64 极简 XFCE4 根文件系统制作脚本
# 打包为 Android 原生 toybox 100% 兼容的 .tar.gz 格式
# ==============================================================================
set -euo pipefail

WORK_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD_DIR="${WORK_DIR}/build/rootfs_build"
OUTPUT_DIR="${WORK_DIR}/app/src/main/assets/rootfs"
ROOTFS_TAR="ubuntu-noble-xfce4-arm64.tar.gz"

UBUNTU_BASE_URL="https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04-base-arm64.tar.gz"

echo "=== [1/5] 创建构建目录 ==="
mkdir -p "${BUILD_DIR}/rootfs"
mkdir -p "${OUTPUT_DIR}"

echo "=== [2/5] 获取 Ubuntu 24.04 LTS aarch64 官方基础镜像 ==="
if [ ! -f "${BUILD_DIR}/ubuntu-base.tar.gz" ]; then
    echo "下载官方 Ubuntu 24.04 基础镜像..."
    curl -L -o "${BUILD_DIR}/ubuntu-base.tar.gz" "${UBUNTU_BASE_URL}"
else
    echo "已存在缓存镜像：${BUILD_DIR}/ubuntu-base.tar.gz"
fi

echo "=== [3/5] 解压基础系统 ==="
tar -xzf "${BUILD_DIR}/ubuntu-base.tar.gz" -C "${BUILD_DIR}/rootfs"

echo "=== [4/5] 写入 ColorOS 平板运行环境基础配置 ==="
cat << 'EOF' > "${BUILD_DIR}/rootfs/etc/resolv.conf"
nameserver 1.1.1.1
nameserver 8.8.8.8
nameserver 114.114.114.114
EOF

cat << 'EOF' > "${BUILD_DIR}/rootfs/etc/hosts"
127.0.0.1   localhost localhost.localdomain
127.0.0.1   oppo-pad
EOF

# 创建初始化脚本：在容器首次启动或 chroot 阶段配置极简桌面、GPU 与 PulseAudio Simple 模块
cat << 'EOF' > "${BUILD_DIR}/rootfs/root/init_desktop.sh"
#!/bin/bash
export DEBIAN_FRONTEND=noninteractive

echo "正在更新软件源并配置极简 XFCE4 与 PulseAudio Simple 协议..."
apt-get update
apt-get install -y --no-install-recommends \
    xfce4 \
    xfce4-terminal \
    dbus-x11 \
    pulseaudio \
    pulseaudio-utils \
    mesa-vulkan-drivers \
    mesa-utils \
    libgl1-mesa-dri \
    fonts-wqy-microhei \
    sudo \
    curl \
    nano

# 配置 PulseAudio 简单协议监听，解决 TCP 私有握手杂音问题
mkdir -p /etc/pulse
cat << 'PULSE_EOF' >> /etc/pulse/default.pa
load-module module-simple-protocol-tcp rate=44100 format=s16le channels=2 source=0 record=true port=4713 listen=127.0.0.1
PULSE_EOF

# 配置普通用户与免密 sudo
useradd -m -s /bin/bash -u 1000 oppo
echo "oppo ALL=(ALL) NOPASSWD:ALL" >> /etc/sudoers

# 清理 apt 缓存缩小镜像体积
apt-get clean
rm -rf /var/lib/apt/lists/*
rm -f /root/init_desktop.sh
echo "初始化配置完成！"
EOF
chmod +x "${BUILD_DIR}/rootfs/root/init_desktop.sh"

echo "=== [5/5] 打包根文件系统为 .tar.gz (Android toybox 原生支持) ==="
cd "${BUILD_DIR}/rootfs"
tar -czf "${OUTPUT_DIR}/${ROOTFS_TAR}" .

echo "=== 根文件系统镜像生成完毕 ==="
echo "输出文件路径: ${OUTPUT_DIR}/${ROOTFS_TAR}"
ls -lh "${OUTPUT_DIR}/${ROOTFS_TAR}"
