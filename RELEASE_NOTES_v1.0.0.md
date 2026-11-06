# ColorOS Linux Virtual Desktop v1.0.0 🚀

这是专为 **OPPO / OnePlus 平板设备** (ColorOS 14 / 15 / 17，Android 14+) 深度定制的 **免 Root 原生 Linux 虚拟桌面系统** 的首个正式版本！

---

## 🌟 核心特性亮点

### 1. 🖥️ 原生 Linux 容器环境 (无需 Root)
- 内置 **Ubuntu 24.04 LTS (Noble Numbat) ARM64** 定制 Rootfs 自动一键安装。
- 采用深度优化的 PRoot + seccomp 架构，二进制封装为原生 JNI 运行库，无惧 Android W^X 安全执行策略与后台顽固查杀。

### 2. ⚡ 120Hz / 144Hz 超低延迟混合渲染架构
- 底层采用现代化 **C++20 原生渲染管线** (`libcoloros_x11_bridge.so`)，直通 `ANativeWindow` 与 EGL/GLESv3。
- 深度适配 OPPO Pad 超高刷屏幕，支持动态帧率绑定与三重缓冲，告别传统 VNC/RDP 串流的卡顿与断连。

### 3. 🖊️ OPPO Pencil 硬件级压感与倾角支持
- 完美适配 OPPO Pencil 4096 级压感、倾角检测（Tilt）与防误触算法。
- Linux 桌面端原画创作无缝对接 **GIMP、Krita、Inkscape** 等专业绘图设计软件。

### 4. 🖥️➕📱 独立双屏生态拓展 (Dual-Screen Mode)
- **有线直连**：支持 USB Type-C DisplayPort 4K/60Hz 外接显示器即插即用。
- **独立双屏分工**：
  - **外接大显示器**：全屏呈现 Linux 沉浸式桌面生产力；
  - **平板本体屏幕**：智能切换为 **高灵敏度触控板 / 跨屏绘图数位板 / 快捷键浮层控制台**。
- 支持单指轻击、双指平滑惯性滚动、三指桌面切换与边缘右键手势。

### 5. 📂 零拷贝双向文件互通与双向剪贴板同步
- 实现 Android `DocumentsProvider` 系统级内容提供者，可在平板自带的“文件”App 中直接挂载、读写、拖拽 Linux 容器内的所有文件。
- 双向文本剪贴板自动纳秒级响应同步。

### 6. 🛡️ 工业级安全与生命周期保障
- 严格防御 Path Traversal (越权路径穿越) 与软链接逃逸删除漏洞。
- 三级 PID 降级守护与双悬浮球快速最小化/切换机制。

---

## 📦 下载与安装指南

1. **直接下载安装**：
   - 点击下方 Assets 中的 `ColorOS-Linux-Desktop-v1.0.0.apk` 下载至平板。
   - 点击安装包完成安装（包体已经过预先签名，允许直接一键安装）。
2. **首次启动**：
   - 打开桌面图标 **Ubuntu Virtual Desktop**。
   - 授予“悬浮窗”与“所有文件访问”权限。
   - 点击 **“一键部署 / 启动系统”**，系统将自动解压环境并进入 Linux 桌面！
3. **外接大屏使用**：
   - 将平板通过 Type-C 转 HDMI/DP 线缆连接显示器，系统自动探测并弹窗询问是否开启独立双屏模式。

---

## 🔒 校验哈希 (SHA256)
```text
f7d6f26ceab7c70eaf1f934a860097fbf9178add26e61bb1759b4a5cffd825c0  ColorOS-Linux-Desktop-v1.0.0.apk
```
文件大小：**2.8 MB** (精炼 Native 混合打包，极速下载)

---

> 📖 **开源开发者教程**：想要自行构建或修改代码？请参阅仓库中的 [DEVELOPER_GUIDE.md](https://github.com/Roland114514/coloros-linux-vm/blob/main/DEVELOPER_GUIDE.md)。
