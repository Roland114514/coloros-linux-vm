# OPPO 平板（ColorOS 17）Linux 虚拟桌面系统架构方案与高精度流程图

## 1. 最终技术规格定型 (Specification)

| 维度 | 规格选择 | 决策依据与技术指标 |
| :--- | :--- | :--- |
| **底层核心** | **方案 A：高性能免 Root 容器 (PRoot)** | 普通 APK 直接安装即可使用，CPU 损耗接近 0%，无视 ColorOS 对 `/dev/kvm` 的封锁。 |
| **图形渲染** | **嵌入式 Termux-X11 + AHardwareBuffer + Zink/Turnip** | 零拷贝共享内存直接送显 Android `SurfaceView`，Adreno GPU 满血 Vulkan 硬件加速，支持 120Hz 高刷。 |
| **交互形态** | **全局置顶悬浮球 (Floating Switch Pill)** | 基于 `SYSTEM_ALERT_WINDOW` 实现随处可点击、磁吸边缘的半透明悬浮球，点击触发系统级极速过渡动画。 |
| **桌面环境** | **Ubuntu 24.04 LTS (Noble) + 触控优化版 XFCE4** | 内存开销极低（常驻空载 < 350MB），冷启动仅需 2~3 秒，对平板触摸与 OPPO Pencil 压感支持极度稳定。 |
| **宿主系统** | **OPPO ColorOS 17 (基于 Android 15/16)** | 配套专属的前台特权服务与防后台智能冻结策略。 |

---

## 2. 核心架构与流程图

### 2.1 全局置顶悬浮球交互与显示层状态转移图
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

    state "系统视图状态 (View Hierarchy)" as SystemView {
        state "ColorOS 前台模式" as ColorOSState {
            ColorOSDesktop: 正在使用 Android 应用/桌面
            LinuxBgKeepAlive: Linux 在后台低耗维持 (1 FPS 挂起)
        }

        state "Linux 前台模式" as LinuxState {
            LinuxDisplayFullscreen: SurfaceView 全屏独占 (120Hz)
            ColorOSBg: Android 系统退居底层
        }
    }

    FloatingBallHover --> LinuxDisplayFullscreen: 【当前在 ColorOS】点击悬浮球，触发扩散放大动画切入 Linux
    FloatingBallHover --> ColorOSDesktop: 【当前在 Linux】点击悬浮球，触发缩起动画退出至 ColorOS 桌面

    LinuxDisplayFullscreen --> ColorOSDesktop: 悬浮球双击/或四指捏合
```

### 2.2 “点击悬浮球一键切换” 端到端时序图
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
    FloatBall->>HostApp: 发起无动画透明过渡 Intent (FLAG_ACTIVITY_REORDER_TO_FRONT)
    HostApp->>WindowMgr: 请求 SurfaceView 渲染层置顶呈现
    HostApp->>CoreSvc: 申请 CPU 性能模式 (HIGH_PERFORMANCE_MODE)
    HostApp->>Bridge: 附着 ANativeWindow，恢复图形流 (Resume)
    Bridge->>X11Server: 解除渲染休眠 (Wakeup X11 Renderer)
    X11Server->>LinuxSys: 恢复 120Hz 垂直同步帧信号 (VSync)
    LinuxSys->>X11Server: 提交当前桌面最新帧缓冲
    X11Server->>Bridge: 零拷贝写入 AHardwareBuffer
    Bridge->>HostApp: 渲染完成上屏
    Note over User, LinuxSys: 耗时 < 150ms，用户无缝进入 Linux 桌面

    Note over User, LinuxSys: 【场景 2：在 Linux 中工作，一键退回 ColorOS】
    User->>FloatBall: 再次单击置顶悬浮球
    FloatBall->>HostApp: 发送最小化指令 (moveTaskToBack)
    HostApp->>WindowMgr: 退回后台，屏幕还给 ColorOS 桌面
    HostApp->>Bridge: 挂起前端 Surface 渲染 (Pause Rendering)
    Bridge->>X11Server: 限制渲染帧率降至 1 FPS (降低功耗与发热)
    HostApp->>CoreSvc: 维持 WakeLock 与前台常驻通知
    Note over User, LinuxSys: Linux 所有终端、编译、浏览器进程在后台继续运行
```

### 2.3 容器底层启动与图形管线流程
```mermaid
flowchart TD
    subgraph "ColorOS 17 宿主空间"
        AppStart([用户首次启动应用]) --> CheckRootfs{Ubuntu Rootfs 是否已释放?}
        CheckRootfs -->|否| ExtractTar[解包预置 Ubuntu 24.04 精简镜像包]
        CheckRootfs -->|是| StartSvc[启动 LinuxHostService 前台保活服务]
        ExtractTar --> StartSvc

        StartSvc --> BindOverlay[申请并在全局添加半透明悬浮球]
        StartSvc --> ForkPRoot[初始化 Native JNI 进程环境]
    end

    subgraph "PRoot 用户态容器环境"
        ForkPRoot --> SetEnv[配置环境变量: DISPLAY=:0, PULSE_SERVER=tcp:127.0.0.1:4713]
        SetEnv --> ExecPRoot["启动 proot (绑定 /dev, /proc, /sys, /sdcard)"]
        ExecPRoot --> LaunchServices["启动后台基础服务 (dbus, pulse)"]
        LaunchServices --> LaunchXFCE4["启动 XFCE4 Session (xfce4-session)"]
    end

    subgraph "图形与显示桥接通道 (Zero-Copy)"
        LaunchXFCE4 -->|X11 协议通信| EmbeddedX11["嵌入式 Termux-X11 服务端 (/tmp/.X11-unix/X0)"]
        EmbeddedX11 -->|Zink + Turnip| VulkanHW["Adreno GPU Vulkan 硬件渲染"]
        VulkanHW -->|输出帧缓冲| AHBuffer["Android AHardwareBuffer (零拷贝共享内存)"]
        AHBuffer -->|直通贴图| NativeSurface["Native Window (SurfaceView)"]
        NativeSurface --> Screen[(OPPO 平板 120Hz 屏幕呈现)]
    end
```
