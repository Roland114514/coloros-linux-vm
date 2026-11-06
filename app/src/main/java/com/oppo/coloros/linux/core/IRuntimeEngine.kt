package com.oppo.coloros.linux.core

interface IRuntimeEngine {
    enum class State {
        STOPPED, STARTING, RUNNING, ERROR
    }

    val currentState: State

    /**
     * 启动容器与 X11 图形桌面环境
     */
    suspend fun bootContainer(onLogOutput: (String) -> Unit): Result<Unit>

    /**
     * 动态调整渲染帧率（前台 120FPS 满血模式 / 后台 1FPS 省电保活模式）
     */
    fun setRenderFrameRate(fps: Int)

    /**
     * 安全终止所有 Linux 进程树
     */
    fun shutdownContainer()
}
