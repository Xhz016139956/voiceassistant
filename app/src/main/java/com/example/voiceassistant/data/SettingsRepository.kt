package com.example.voiceassistant.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

/**
 * 贾维斯的可配置项。
 *
 * API Key 目前明文存 DataStore：自用侧载，且离线构建不允许引入
 * security-crypto。真要分发再换 EncryptedSharedPreferences。
 */
data class JarvisSettings(
    val wakeWordEnabled: Boolean = false,
    val ttsMuted: Boolean = false,
    val llmBaseUrl: String = "",
    val llmApiKey: String = "",
    val llmModel: String = "",
    /** 语音：空 = 跟随系统默认引擎/发音人 */
    val ttsEngine: String = "",
    val ttsVoice: String = "",
    val ttsPitch: Float = 1f,
    val ttsRate: Float = 1f
) {
    val llmConfigured: Boolean get() = llmBaseUrl.isNotBlank() && llmModel.isNotBlank()
}

class SettingsRepository(private val context: Context) {

    private object Keys {
        val ASSISTANT_ENABLED = booleanPreferencesKey("assistant_enabled")
        val TTS_MUTED = booleanPreferencesKey("tts_muted")
        val WAKE_ENABLED = booleanPreferencesKey("wake_word_enabled")
        val LLM_BASE_URL = stringPreferencesKey("llm_base_url")
        val LLM_API_KEY = stringPreferencesKey("llm_api_key")
        val LLM_MODEL = stringPreferencesKey("llm_model")
        val TTS_ENGINE = stringPreferencesKey("tts_engine")
        val TTS_VOICE = stringPreferencesKey("tts_voice")
        val TTS_PITCH = floatPreferencesKey("tts_pitch")
        val TTS_RATE = floatPreferencesKey("tts_rate")
    }

    val assistantEnabled: Flow<Boolean> =
        context.settingsDataStore.data.map { it[Keys.ASSISTANT_ENABLED] ?: false }

    val ttsMuted: Flow<Boolean> =
        context.settingsDataStore.data.map { it[Keys.TTS_MUTED] ?: false }

    val jarvis: Flow<JarvisSettings> = context.settingsDataStore.data.map { prefs ->
        JarvisSettings(
            wakeWordEnabled = prefs[Keys.WAKE_ENABLED] ?: false,
            ttsMuted = prefs[Keys.TTS_MUTED] ?: false,
            llmBaseUrl = prefs[Keys.LLM_BASE_URL].orEmpty(),
            llmApiKey = prefs[Keys.LLM_API_KEY].orEmpty(),
            llmModel = prefs[Keys.LLM_MODEL].orEmpty(),
            ttsEngine = prefs[Keys.TTS_ENGINE].orEmpty(),
            ttsVoice = prefs[Keys.TTS_VOICE].orEmpty(),
            ttsPitch = prefs[Keys.TTS_PITCH] ?: 1f,
            ttsRate = prefs[Keys.TTS_RATE] ?: 1f
        )
    }

    suspend fun setAssistantEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.ASSISTANT_ENABLED] = enabled }
    }

    suspend fun setTtsMuted(muted: Boolean) {
        context.settingsDataStore.edit { it[Keys.TTS_MUTED] = muted }
    }

    suspend fun setWakeWordEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.WAKE_ENABLED] = enabled }
    }

    suspend fun setTtsVoice(engine: String, voice: String, pitch: Float, rate: Float) {
        context.settingsDataStore.edit {
            it[Keys.TTS_ENGINE] = engine.trim()
            it[Keys.TTS_VOICE] = voice.trim()
            it[Keys.TTS_PITCH] = pitch
            it[Keys.TTS_RATE] = rate
        }
    }

    suspend fun setLlm(baseUrl: String, apiKey: String, model: String) {
        context.settingsDataStore.edit {
            it[Keys.LLM_BASE_URL] = baseUrl.trim()
            it[Keys.LLM_API_KEY] = apiKey.trim()
            it[Keys.LLM_MODEL] = model.trim()
        }
    }
}
