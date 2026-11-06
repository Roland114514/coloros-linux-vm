#include <jni.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <android/log.h>
#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <unistd.h>
#include <thread>
#include <mutex>
#include <condition_variable>
#include <atomic>
#include <vector>
#include <chrono>

#define LOG_TAG "NativeDisplayBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {
    std::mutex g_syncMutex;
    std::condition_variable g_syncCv;

    ANativeWindow* g_pendingWindow = nullptr;
    int g_surfaceWidth = 1920;
    int g_surfaceHeight = 1080;
    int g_surfaceDpi = 320;

    bool g_surfaceChanged = false;
    bool g_detachRequested = false;
    bool g_surfaceDetachedAck = true;

    std::atomic<bool> g_engineRunning{false};
    std::atomic<int> g_targetFrameRate{120};
    std::thread g_renderThread;

    std::mutex g_frameMutex;
    std::vector<uint8_t> g_frameBuffer;
    int g_frameWidth = 1280;
    int g_frameHeight = 800;
    bool g_hasNewFrame = false;

    void applyHighRefreshRate(ANativeWindow* window, int fps) {
        if (!window) return;
        #if __ANDROID_API__ >= 30
        ANativeWindow_setFrameRate(
            window, 
            static_cast<float>(fps), 
            ANATIVEWINDOW_FRAME_RATE_COMPATIBILITY_FIXED_SOURCE
        );
        #endif
    }

    void renderLoop() {
        LOGI("ColorOS Linux Compositor Render Thread Started.");
        EGLDisplay display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        eglInitialize(display, nullptr, nullptr);

        const EGLint configAttribs[] = {
            EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
            EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
            EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
            EGL_NONE
        };
        EGLConfig config;
        EGLint numConfigs;
        eglChooseConfig(display, configAttribs, &config, 1, &numConfigs);

        const EGLint contextAttribs[] = { EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE };
        EGLContext context = eglCreateContext(display, config, EGL_NO_CONTEXT, contextAttribs);

        EGLSurface surface = EGL_NO_SURFACE;
        ANativeWindow* currentWindow = nullptr;

        while (g_engineRunning.load()) {
            {
                std::unique_lock<std::mutex> lock(g_syncMutex);

                // 1. 优先响应 Surface Detach 请求，确保 Adreno GPU 在 Window 销毁前彻底解绑
                if (g_detachRequested) {
                    if (surface != EGL_NO_SURFACE) {
                        eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
                        eglDestroySurface(display, surface);
                        surface = EGL_NO_SURFACE;
                    }
                    if (currentWindow != nullptr) {
                        ANativeWindow_release(currentWindow);
                        currentWindow = nullptr;
                    }
                    g_detachRequested = false;
                    g_surfaceDetachedAck = true;
                    g_syncCv.notify_all();
                    LOGI("Surface Detached: EGL Surface cleanly destroyed, Adreno GPU barrier resolved.");
                }

                // 2. 响应新 Surface 挂载或尺寸变更
                if (g_surfaceChanged) {
                    if (surface != EGL_NO_SURFACE) {
                        eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
                        eglDestroySurface(display, surface);
                        surface = EGL_NO_SURFACE;
                    }
                    if (currentWindow != nullptr) {
                        ANativeWindow_release(currentWindow);
                        currentWindow = nullptr;
                    }
                    if (g_pendingWindow != nullptr) {
                        currentWindow = g_pendingWindow;
                        g_pendingWindow = nullptr;
                        surface = eglCreateWindowSurface(display, config, currentWindow, nullptr);
                        if (surface != EGL_NO_SURFACE) {
                            eglMakeCurrent(display, surface, surface, context);
                            eglSwapInterval(display, 1);
                            applyHighRefreshRate(currentWindow, g_targetFrameRate.load());
                            glViewport(0, 0, g_surfaceWidth, g_surfaceHeight);
                            LOGI("Surface Attached: %dx%d @ %dHz", g_surfaceWidth, g_surfaceHeight, g_targetFrameRate.load());
                        } else {
                            LOGE("Failed to create EGLWindowSurface: 0x%x", eglGetError());
                            ANativeWindow_release(currentWindow);
                            currentWindow = nullptr;
                        }
                    }
                    g_surfaceChanged = false;
                    g_surfaceDetachedAck = (surface == EGL_NO_SURFACE);
                    g_syncCv.notify_all();
                }

                // 后台休眠避免空转
                if (surface == EGL_NO_SURFACE && g_engineRunning.load()) {
                    g_syncCv.wait_for(lock, std::chrono::milliseconds(50));
                    continue;
                }
            }

            // 3. 执行安全渲染与帧交换
            if (surface != EGL_NO_SURFACE) {
                glClearColor(0.08f, 0.09f, 0.12f, 1.0f);
                glClear(GL_COLOR_BUFFER_BIT);

                if (!eglSwapBuffers(display, surface)) {
                    EGLint err = eglGetError();
                    if (err == EGL_BAD_SURFACE || err == EGL_BAD_NATIVE_WINDOW) {
                        LOGW("eglSwapBuffers detected invalidated window (0x%x), scheduling detach...", err);
                        std::lock_guard<std::mutex> lock(g_syncMutex);
                        g_detachRequested = true;
                    }
                }
            }

            std::this_thread::sleep_for(std::chrono::milliseconds(8)); // 支持高达 120 FPS
        }

        if (surface != EGL_NO_SURFACE) {
            eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            eglDestroySurface(display, surface);
            surface = EGL_NO_SURFACE;
        }
        if (currentWindow != nullptr) {
            ANativeWindow_release(currentWindow);
            currentWindow = nullptr;
        }
        {
            std::lock_guard<std::mutex> lock(g_syncMutex);
            if (g_pendingWindow != nullptr) {
                ANativeWindow_release(g_pendingWindow);
                g_pendingWindow = nullptr;
            }
            g_surfaceDetachedAck = true;
            g_syncCv.notify_all();
        }
        if (context != EGL_NO_CONTEXT) eglDestroyContext(display, context);
        eglTerminate(display);
        LOGI("ColorOS Linux Compositor Render Thread Terminated.");
    }
}

extern "C" {

JNIEXPORT void JNICALL
Java_com_oppo_coloros_linux_core_NativeDisplayBridge_attachSurface(
        JNIEnv* env, jclass /*clazz*/, jobject surface, jint width, jint height, jint dpi) {
    if (surface == nullptr) return;
    ANativeWindow* newWindow = ANativeWindow_fromSurface(env, surface);

    std::unique_lock<std::mutex> lock(g_syncMutex);
    if (g_pendingWindow != nullptr) {
        ANativeWindow_release(g_pendingWindow);
    }
    g_pendingWindow = newWindow;
    g_surfaceWidth = width > 0 ? width : 1920;
    g_surfaceHeight = height > 0 ? height : 1080;
    g_surfaceDpi = dpi > 0 ? dpi : 320;
    g_surfaceChanged = true;
    g_detachRequested = false;
    g_surfaceDetachedAck = false;
    g_syncCv.notify_all();

    if (!g_engineRunning.load()) {
        g_engineRunning.store(true);
        g_renderThread = std::thread(renderLoop);
    }
}

JNIEXPORT void JNICALL
Java_com_oppo_coloros_linux_core_NativeDisplayBridge_detachSurface(JNIEnv* /*env*/, jclass /*clazz*/) {
    std::unique_lock<std::mutex> lock(g_syncMutex);
    if (g_pendingWindow != nullptr) {
        ANativeWindow_release(g_pendingWindow);
        g_pendingWindow = nullptr;
    }
    g_surfaceChanged = false;
    g_detachRequested = true;
    g_syncCv.notify_all();

    // 同步屏障：等待渲染线程完成 eglMakeCurrent(NO_SURFACE)
    // 杜绝 Adreno GPU 在 SurfaceView 销毁或切后台时的 SEGV 崩溃
    if (g_engineRunning.load()) {
        g_syncCv.wait_for(lock, std::chrono::milliseconds(500), [] {
            return g_surfaceDetachedAck || !g_engineRunning.load();
        });
    }
    LOGI("Surface safely detached with Adreno GPU sync barrier.");
}

JNIEXPORT void JNICALL
Java_com_oppo_coloros_linux_core_NativeDisplayBridge_setTargetFps(JNIEnv* /*env*/, jclass /*clazz*/, jint fps) {
    g_targetFrameRate.store(fps);
}

JNIEXPORT void JNICALL
Java_com_oppo_coloros_linux_core_NativeDisplayBridge_sendPointerEvent(
        JNIEnv* /*env*/, jclass /*clazz*/, jfloat /*x*/, jfloat /*y*/, jint /*action*/, jint /*pointerId*/, jfloat /*pressure*/) {
    // 多点触控转发
}

JNIEXPORT void JNICALL
Java_com_oppo_coloros_linux_core_NativeDisplayBridge_sendStylusEvent(
        JNIEnv* /*env*/, jclass /*clazz*/, jfloat /*x*/, jfloat /*y*/, jint /*action*/, jfloat /*pressure*/, jfloat /*tilt*/, jboolean /*isEraser*/) {
    // OPPO Pencil 压感与倾角透传
}

JNIEXPORT void JNICALL
Java_com_oppo_coloros_linux_core_NativeDisplayBridge_sendRelativeMouseEvent(
        JNIEnv* /*env*/, jclass /*clazz*/, jfloat /*dx*/, jfloat /*dy*/, jint /*buttonState*/) {
    // 鼠标指针捕获位移
}

JNIEXPORT void JNICALL
Java_com_oppo_coloros_linux_core_NativeDisplayBridge_sendScrollEvent(
        JNIEnv* /*env*/, jclass /*clazz*/, jfloat /*vScroll*/, jfloat /*hScroll*/) {
    // 滚轮事件
}

JNIEXPORT void JNICALL
Java_com_oppo_coloros_linux_core_NativeDisplayBridge_sendKeyEvent(
        JNIEnv* /*env*/, jclass /*clazz*/, jint /*keyCode*/, jboolean /*isDown*/) {
    // 键盘按键事件
}

} // extern "C"
