package com.example.voiceassistant

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.example.voiceassistant.data.LogStage
import com.example.voiceassistant.di.ServiceLocator
import com.example.voiceassistant.llm.LlmUrls
import com.example.voiceassistant.state.LlmStatusBus
import com.example.voiceassistant.ui.AppRoot
import com.example.voiceassistant.ui.theme.VoiceAssistantTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VoiceAssistantTheme {
                AppRoot()
            }
        }
        importLlmConfigFromIntent(intent)
        handleTtsIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        importLlmConfigFromIntent(intent)
        handleTtsIntent(intent)
    }

    /**
     * 允许用启动参数直接写配置，方便 adb 部署/排障：
     *
     *   adb shell am start -n com.example.voiceassistant/.MainActivity \
     *     --es llm_base_url http://127.0.0.1:11434 --es llm_model qwen3:1.7b
     *
     * 写完后会立刻探测一次，把"通不通、缺什么"写进日志。
     */
    private fun importLlmConfigFromIntent(intent: Intent?) {
        val extras = intent ?: return
        val rawUrl = extras.getStringExtra(EXTRA_BASE_URL)?.trim().orEmpty()
        if (rawUrl.isEmpty()) return

        val model = extras.getStringExtra(EXTRA_MODEL)?.trim().orEmpty()
        val apiKey = extras.getStringExtra(EXTRA_API_KEY)?.trim().orEmpty()
        val root = LlmUrls.root(rawUrl)

        lifecycleScope.launch {
            LlmStatusBus.testing()
            val probe = ServiceLocator.llmProbe.probe(root, apiKey, model)
            LlmStatusBus.publish(probe.ok, probe.message)
            val finalModel = when {
                model.isNotBlank() && probe.ok -> model
                probe.suggestedModel != null -> probe.suggestedModel
                else -> model
            }
            ServiceLocator.settingsRepository.setLlm(root, apiKey, finalModel)
            ServiceLocator.logRepository.add(
                LogStage.LLM,
                "启动参数写入配置：$root / ${finalModel.ifBlank { "未确定" }} · ${probe.message}",
                ok = probe.ok
            )
        }
    }

    /**
     * 语音调试入口（adb 部署/排障用）：
     *
     *   adb shell am start -n com.example.voiceassistant/.MainActivity \
     *     --es tts_engine org.nobody.multitts --es tts_list 1
     *   adb shell am start ... --es tts_voice zh-CN-YunxiNeural --es tts_speak "俺老孙来也"
     *
     * --es tts_list 1 会把当前引擎报出的发音人写进日志，便于确认导入是否生效。
     */
    private fun handleTtsIntent(intent: Intent) {
        val engineExtra = intent.getStringExtra(EXTRA_TTS_ENGINE)
        val voiceExtra = intent.getStringExtra(EXTRA_TTS_VOICE)
        val speakExtra = intent.getStringExtra(EXTRA_TTS_SPEAK)
        val list = intent.getStringExtra(EXTRA_TTS_LIST) != null
        val pitchExtra = intent.getStringExtra(EXTRA_TTS_PITCH)?.toFloatOrNull()
        val rateExtra = intent.getStringExtra(EXTRA_TTS_RATE)?.toFloatOrNull()

        if (engineExtra == null && voiceExtra == null && speakExtra == null &&
            pitchExtra == null && rateExtra == null && !list
        ) {
            return
        }

        lifecycleScope.launch {
            val current = ServiceLocator.jarvisSettings
            val engine = engineExtra ?: current.ttsEngine
            val voice = voiceExtra ?: current.ttsVoice
            val pitch = pitchExtra ?: current.ttsPitch
            val rate = rateExtra ?: current.ttsRate

            if (engineExtra != null || voiceExtra != null || pitchExtra != null || rateExtra != null) {
                ServiceLocator.settingsRepository.setTtsVoice(
                    engine = engine,
                    voice = voice,
                    pitch = pitch,
                    rate = rate
                )
                ServiceLocator.tts.applyConfig(engine, voice, pitch, rate)
            }

            if (list || engineExtra != null) {
                // 引擎初始化是异步的，等一会儿再取发音人清单
                repeat(12) { delay(500) }
                val voices = ServiceLocator.tts.voices()
                Log.i(
                    TAG,
                    "engine=${engine.ifBlank { "default" }} ready=${ServiceLocator.tts.isReady()} " +
                        "voices=${voices.joinToString { "${it.key}:${it.label}" }.ifBlank { "（无）" }}"
                )
                ServiceLocator.logRepository.add(
                    LogStage.SYSTEM,
                    "发音人清单（${engine.ifBlank { "系统默认" }}）：${
                        voices.joinToString("、") { it.label }.ifBlank { "空" }
                    }"
                )
            }

            if (speakExtra != null) {
                ServiceLocator.tts.preview(speakExtra)
            }
        }
    }

    private companion object {
        const val TAG = "JarvisDebug"
        const val EXTRA_BASE_URL = "llm_base_url"
        const val EXTRA_MODEL = "llm_model"
        const val EXTRA_API_KEY = "llm_api_key"
        const val EXTRA_TTS_ENGINE = "tts_engine"
        const val EXTRA_TTS_VOICE = "tts_voice"
        const val EXTRA_TTS_SPEAK = "tts_speak"
        const val EXTRA_TTS_LIST = "tts_list"
        const val EXTRA_TTS_PITCH = "tts_pitch"
        const val EXTRA_TTS_RATE = "tts_rate"
    }
}
