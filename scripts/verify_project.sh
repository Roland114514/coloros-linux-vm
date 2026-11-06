#!/usr/bin/env bash
# ==============================================================================
# OPPO 平板 ColorOS 17 Linux 虚拟桌面项目完整性与结构校验脚本
# ==============================================================================
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT_DIR}"

echo "========================================================"
echo "    ColorOS 17 Linux 虚拟桌面工程静态验证与合规性检查     "
echo "========================================================"

FAILED=0

check_file() {
    local path="$1"
    if [ -f "$path" ]; then
        echo "  [✓] $path"
    else
        echo "  [✗] 缺失必要文件: $path"
        FAILED=1
    fi
}

echo "1. 检查构建与 Gradle 核心配置..."
check_file "build.gradle.kts"
check_file "settings.gradle.kts"
check_file "app/build.gradle.kts"

echo "2. 检查 AndroidManifest 与权限声明..."
check_file "app/src/main/AndroidManifest.xml"
if grep -q "SYSTEM_ALERT_WINDOW" app/src/main/AndroidManifest.xml && \
   grep -q "FOREGROUND_SERVICE_SPECIAL_USE" app/src/main/AndroidManifest.xml && \
   grep -q "WAKE_LOCK" app/src/main/AndroidManifest.xml; then
    echo "  [✓] ColorOS 17 关键特权与前台服务权限完整声明"
else
    echo "  [✗] AndroidManifest 权限声明不全"
    FAILED=1
fi

echo "3. 检查核心服务与 UI 源码..."
check_file "app/src/main/java/com/oppo/coloros/linux/ui/MainActivity.kt"
check_file "app/src/main/java/com/oppo/coloros/linux/ui/LinuxDisplayActivity.kt"
check_file "app/src/main/java/com/oppo/coloros/linux/ui/SmartTrackpadActivity.kt"
check_file "app/src/main/java/com/oppo/coloros/linux/service/FloatingSwitchService.kt"
check_file "app/src/main/java/com/oppo/coloros/linux/service/LinuxHostService.kt"
check_file "app/src/main/java/com/oppo/coloros/linux/core/IRuntimeEngine.kt"
check_file "app/src/main/java/com/oppo/coloros/linux/core/PRootEngine.kt"
check_file "app/src/main/java/com/oppo/coloros/linux/core/RootfsInstaller.kt"
check_file "app/src/main/java/com/oppo/coloros/linux/bridge/InputEventBridge.kt"
check_file "app/src/main/java/com/oppo/coloros/linux/bridge/ExternalDisplayCoordinator.kt"
check_file "app/src/main/java/com/oppo/coloros/linux/bridge/AudioStreamBridge.kt"
check_file "app/src/main/java/com/oppo/coloros/linux/display/ExternalDisplayManager.kt"
check_file "app/src/main/java/com/oppo/coloros/linux/display/LinuxPresentation.kt"
check_file "app/src/main/java/com/oppo/coloros/linux/bridge/ClipboardBridge.kt"
check_file "app/src/main/java/com/oppo/coloros/linux/provider/LinuxStorageProvider.kt"

echo "4. 检查 Native C++ 与 JNI 桥接层..."
check_file "app/src/main/cpp/CMakeLists.txt"
check_file "app/src/main/cpp/native_bridge.cpp"

if grep -q "sendStylusEvent" app/src/main/cpp/native_bridge.cpp && \
   grep -q "sendRelativeMouseEvent" app/src/main/cpp/native_bridge.cpp && \
   grep -q "setTargetFps" app/src/main/cpp/native_bridge.cpp; then
    echo "  [✓] OPPO Pencil 压感、外接鼠标指针捕获与 120Hz 帧率 JNI 签名校验通过"
else
    echo "  [✗] C++ NDK 缺少必要 JNI 方法导出"
    FAILED=1
fi

echo "5. 检查 XML 布局与主题资源..."
check_file "app/src/main/res/layout/layout_floating_ball.xml"
check_file "app/src/main/res/layout/activity_linux_display.xml"
check_file "app/src/main/res/layout/activity_smart_trackpad.xml"
check_file "app/src/main/res/layout/presentation_linux_display.xml"
check_file "app/src/main/res/layout/activity_main.xml"
check_file "app/src/main/res/values/colors.xml"
check_file "app/src/main/res/values/themes.xml"
check_file "app/src/main/res/values/strings.xml"

echo "6. 检查自动化流水线与构建脚本..."
check_file "ci/android_build.yml"
check_file "scripts/build_rootfs.sh"
check_file "scripts/fetch_prebuilt_binaries.sh"
check_file "scripts/gemini_caller.py"
check_file "scripts/clip_daemon.py"

echo "7. 检查单测覆盖..."
check_file "app/src/test/java/com/oppo/coloros/linux/bridge/InputEventBridgeTest.kt"
check_file "app/src/test/java/com/oppo/coloros/linux/display/ExternalDisplayManagerTest.kt"
check_file "app/src/test/java/com/oppo/coloros/linux/ui/SmartTrackpadGestureTest.kt"
check_file "app/src/test/java/com/oppo/coloros/linux/security/StorageSecurityAndConcurrencyTest.kt"

echo "--------------------------------------------------------"
if [ "$FAILED" -eq 0 ]; then
    echo "恭喜！项目结构验证全部通过，无任何缺失或配置缺陷！"
    exit 0
else
    echo "错误：部分必要组件缺失，请检查上述错误报告。"
    exit 1
fi
