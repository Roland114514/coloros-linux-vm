package com.oppo.coloros.linux.bridge

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.util.Log
import java.io.BufferedInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 接收 Linux PulseAudio module-simple-protocol-tcp 输出的原始 PCM 流 (44100Hz, 16-bit, Stereo)
 * 具备断线指数退避重连、防阻塞环形缓冲与低延迟硬件播放能力
 */
class AudioStreamBridge(private val port: Int = 4713) {
    companion object {
        private const val TAG = "AudioStreamBridge"
        private const val HOST = "127.0.0.1"
        private const val SAMPLE_RATE = 44100
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_STEREO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val CONNECT_TIMEOUT_MS = 2500
        private const val READ_TIMEOUT_MS = 5000
    }

    private val isRunning = AtomicBoolean(false)
    private var workerThread: Thread? = null
    @Volatile
    private var activeSocket: Socket? = null

    fun start() {
        if (!isRunning.compareAndSet(false, true)) return

        workerThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            runAudioLoop()
        }, "PulseSimpleAudioThread").apply {
            isDaemon = true
            start()
        }
    }

    private fun runAudioLoop() {
        val minBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufferSize = maxOf(minBufferSize * 4, 8192)

        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AUDIO_FORMAT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(CHANNEL_CONFIG)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()

        val pcmBuffer = ByteArray(4096)
        var retryDelayMs = 500L

        try {
            audioTrack.play()

            while (isRunning.get()) {
                var socket: Socket? = null
                try {
                    socket = Socket()
                    activeSocket = socket
                    socket.tcpNoDelay = true
                    socket.receiveBufferSize = bufferSize
                    socket.soTimeout = READ_TIMEOUT_MS
                    socket.connect(InetSocketAddress(HOST, port), CONNECT_TIMEOUT_MS)

                    Log.i(TAG, "Connected to Linux PulseAudio Simple Protocol TCP server on port $port")
                    retryDelayMs = 500L

                    val inputStream = BufferedInputStream(socket.getInputStream(), bufferSize)

                    while (isRunning.get() && !socket.isClosed) {
                        val bytesRead = inputStream.read(pcmBuffer)
                        if (bytesRead == -1) {
                            Log.w(TAG, "PulseAudio stream reached EOF, reconnecting...")
                            break
                        }
                        if (bytesRead > 0) {
                            val alignedBytes = bytesRead - (bytesRead % 4)
                            if (alignedBytes > 0) {
                                audioTrack.write(pcmBuffer, 0, alignedBytes, AudioTrack.WRITE_BLOCKING)
                            }
                        }
                    }
                } catch (e: IOException) {
                    if (isRunning.get()) {
                        try {
                            Thread.sleep(retryDelayMs)
                            retryDelayMs = minOf(retryDelayMs * 2, 3000L)
                        } catch (ie: InterruptedException) {
                            Thread.currentThread().interrupt()
                            break
                        }
                    }
                } finally {
                    try {
                        socket?.close()
                    } catch (_: Exception) {}
                    activeSocket = null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fatal error in AudioTrack playback", e)
        } finally {
            try {
                if (audioTrack.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    audioTrack.stop()
                }
            } catch (_: Exception) {}
            audioTrack.release()
            Log.i(TAG, "AudioStreamBridge terminated cleanly.")
        }
    }

    fun stop() {
        if (!isRunning.compareAndSet(true, false)) return
        try {
            activeSocket?.close()
        } catch (_: Exception) {}
        workerThread?.interrupt()
        try {
            workerThread?.join(1000)
        } catch (_: InterruptedException) {}
        workerThread = null
    }
}
