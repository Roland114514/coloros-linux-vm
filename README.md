# OPPO ColorOS 17 Linux 虚拟桌面与一键切换系统

本项目致力于在 OPPO 平板（ColorOS 17，基于 Android 15/16）上构建一款具备桌面级体验的 Linux 运行环境，并实现**全局悬浮球“一键切换系统”**（在 Android 与 Ubuntu 桌面之间无缝极速切换）。

---

## 核心特性与技术规格

- **底层引擎**：高性能免 Root 用户态容器（PRoot + Box64）。
- **图形渲染管线**：嵌入式 Termux-X11 + AHardwareBuffer + Zink/Turnip (Adreno GPU 满血 Vulkan 硬件加速，支持 120Hz 刷新率)。
- **一键切换交互**：全局半透明磁吸悬浮球（基于 `SYSTEM_ALERT_WINDOW`），支持随处触控吸附、一键切换、3D 展开/收起过渡动效。
- **预置桌面环境**：Ubuntu 24.04 LTS (Noble aarch64) + 触控优化版 XFCE4 桌面环境（内存常驻 < 350MB，冷启动仅需 2~3 秒）。
- **ColorOS 17 专项保活**：持有 WakeLock 与前台特权服务（`specialUse`），避免后台运行被 ColorOS 智能冻结或 LMK 查杀。

---

## 核心架构流程

### 1. 全局悬浮球交互与双系统状态转移图
```mermaid
stateDiagram-v2
    [*] --> FloatingBallHidden: 应用未启动
    FloatingBallHidden --> FloatingBallActive: 启动应用 & 授予悬浮窗权限

    state "全局悬浮球状态" as FloatingBallState {
        FloatingBallActive: 半透明常驻吸边 (透明度 40%)
        FloatingBallDragging: 用户拖拽调整位置
        FloatingBallHover: 触控接近或点击瞬间
        
        FloatingBallActive --> FloatingBallDragging: 手势按下并移动
        FloatingBallDragging --> FloatingBallActive: 手指抬起，自动平滑吸附至最近侧边
        FloatingBallActive --> FloatingBallHover: 单击触发
    }

    state "系统视图状态" as SystemView {
        state "ColorOS 前台模式" as ColorOSState {
            ColorOSDesktop: 正在使用 Android 应用/桌面
            LinuxBgKeepAlive: Linux 在后台低耗维持 (1 FPS 挂起)
        }

        state "Linux 前台模式" as LinuxState {
            LinuxDisplayFullscreen: SurfaceView 全屏独占 (120Hz)
            ColorOSBg: Android 系统退居底层
        }
    }

    FloatingBallHover --> LinuxDisplayFullscreen: 【当前在 ColorOS】点击悬浮球，切入 Linux
    FloatingBallHover --> ColorOSDesktop: 【当前在 Linux】点击悬浮球，退回 ColorOS 桌面
```

### 2. 点击悬浮球一键切换时序图
```mermaid
sequenceDiagram
    autonumber
    actor User as 用户 (OPPO 平板)
    participant FloatBall as FloatingSwitchView (悬浮球)
    participant WindowMgr as Android WindowManager
    participant HostApp as LinuxDisplayActivity (全屏视图)
    participant CoreSvc as LinuxHostService (前台保活服务)
    participant Bridge as Native Display Bridge (C++ NDK)
    participant X11Server as Embedded Termux-X11
    participant LinuxSys as Ubuntu 24.04 (XFCE4 桌面)

    Note over User, LinuxSys: 【场景 1：当前在 ColorOS，一键切换至 Linux】
    User->>FloatBall: 单击悬浮球 (Tap)
    FloatBall->>FloatBall: 触发点击动效 (呼吸光圈收缩)
    FloatBall->>HostApp: 发起透明过渡 Intent (FLAG_ACTIVITY_REORDER_TO_FRONT)
    HostApp->>WindowMgr: 请求 SurfaceView 渲染层置顶呈现
    HostApp->>Bridge: 附着 ANativeWindow，恢复图形流 (Resume)
    Bridge->>X11Server: 解除渲染休眠 (Wakeup X11 Renderer)
    X11Server->>LinuxSys: 恢复 120Hz 垂直同步帧信号 (VSync)
    LinuxSys->>Bridge: 零拷贝写入 AHardwareBuffer
    Bridge->>HostApp: 渲染完成上屏 (< 150ms)

    Note over User, LinuxSys: 【场景 2：在 Linux 中工作，一键退回 ColorOS】
    User->>FloatBall: 再次单击置顶悬浮球
    FloatBall->>HostApp: 发送最小化指令 (moveTaskToBack)
    HostApp->>WindowMgr: 退回后台，屏幕还给 ColorOS 桌面
    HostApp->>Bridge: 挂起前端 Surface 渲染 (Pause Rendering, 降频省电)
    HostApp->>CoreSvc: 维持 WakeLock 与前台常驻通知
```

---

## 目录结构规划

```
├── app/
│   ├── src/main/
│   │   ├── java/com/oppo/linuxvm/
│   │   │   ├── ui/overlay/FloatingSwitchService.kt   # 全局置顶悬浮球服务
│   │   │   ├── ui/LinuxDisplayActivity.kt           # 全屏 Linux 视口 SurfaceView
│   │   │   ├── service/LinuxHostService.kt          # 前台保活与 Wakelock 守护
│   │   │   ├── bridge/NativeBridge.kt               # JNI 接口包装
│   │   │   └── engine/PRootEngine.kt                # PRoot 容器引擎
│   │   ├── cpp/                                     # NDK 图形与零拷贝渲染桥接
│   │   └── assets/                                  # 预置二进制与精简 Ubuntu rootfs
├── docs/                                            # 架构文档与流程设计
└── scripts/                                         # 自动化构建脚本
```
