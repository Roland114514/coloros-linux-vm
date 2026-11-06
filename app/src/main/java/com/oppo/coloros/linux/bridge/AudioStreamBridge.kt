package com.oppo.coloros.linux.bridge

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * PulseAudio TCP 本地服务端音频直通桥接
 * Linux 内部通过环境变量 PULSE_SERVER=tcp:127.0.0.1:4713 将音频流实时注入 Android AudioTrack
 */
class AudioStreamBridge(private val port: Int = 4713) {

    companion object {
        private const val TAG = "AudioStreamBridge"
        private const val SAMPLE_RATE = 44100
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_STEREO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var serverSocket: ServerSocket? = null
    private var audioTrack: AudioTrack? = null
    private var bridgeJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    @Volatile
    private var isRunning = false

    fun start() {
        if (isRunning) return
        isRunning = true

        bridgeJob = scope.launch {
            try {
                initAudioTrack()
                serverSocket = ServerSocket(port, 5, InetAddress.getByName("127.0.0.1"))
                Log.i(TAG, "AudioStreamBridge listening on 127.0.0.1:$port")

                while (isActive && isRunning) {
                    val clientSocket = serverSocket?.accept() ?: break
                    Log.i(TAG, "Client connected to audio bridge: ${clientSocket.inetAddress}")
                    handleClient(clientSocket)
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Log.e(TAG, "Error in audio server loop", e)
                }
            } finally {
                stop()
            }
        }
    }

    private fun initAudioTrack() {
        val minBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufferSize = minBufferSize * 2

        audioTrack = AudioTrack.Builder()
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
            .build().apply {
                play()
            }
    }

    private fun handleClient(socket: Socket) {
        scope.launch {
            try {
                socket.tcpNoDelay = true
                val inputStream: InputStream = socket.getInputStream()
                val buffer = ByteArray(4096)
                var bytesRead: Int

                while (isRunning && inputStream.read(buffer).also { bytesRead = it } != -1) {
                    audioTrack?.write(buffer, 0, bytesRead)
                }
            } catch (e: Exception) {
                Log.d(TAG, "Audio stream client closed: ${e.message}")
            } finally {
                try {
                    socket.close()
                } catch (_: Exception) {}
            }
        }
    }

    fun stop() {
        isRunning = false
        bridgeJob?.cancel()
        bridgeJob = null

        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null

        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) {}
        audioTrack = null
        Log.i(TAG, "AudioStreamBridge stopped.")
    }
}
