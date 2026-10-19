package com.example.voiceassistant.asr

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.sqrt

/**
 * 麦克风电平源：真正的录音采集，只用来驱动悬浮球波形，不保存音频。
 *
 * 模块 3 接入 SpeechRecognizer 后，两者不能同时占用麦克风，
 * 届时把波形数据源换成 AsrEngine.Listener.onRmsChanged 即可，接口形状一致。
 */
class RecordingLevelSource {

    @Volatile
    private var running = false

    private var worker: Thread? = null

    val isRunning: Boolean get() = running

    fun start(onLevel: (Float) -> Unit): Boolean {
        if (running) return false

        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuffer <= 0) return false

        val record = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                CHANNEL,
                ENCODING,
                minBuffer * 2
            )
        }.getOrNull() ?: return false

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }

        running = true
        worker = Thread {
            val buffer = ShortArray(minBuffer)
            val started = runCatching { record.startRecording() }.isSuccess
            if (!started) running = false

            while (running) {
                val read = record.read(buffer, 0, buffer.size)
                if (read > 0) {
                    var sum = 0.0
                    for (i in 0 until read) {
                        val sample = buffer[i] / 32768.0
                        sum += sample * sample
                    }
                    val rms = sqrt(sum / read)
                    // 说话时 RMS 大致 0.01~0.2，×6 拉到 0~1 观感更明显
                    onLevel((rms * 6).coerceIn(0.0, 1.0).toFloat())
                } else {
                    runCatching { Thread.sleep(20) }
                }
            }

            runCatching { record.stop() }
            record.release()
        }.apply {
            name = "voice-level"
            isDaemon = true
            start()
        }

        return true
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { worker?.join(400) }
        worker = null
    }

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }
}
