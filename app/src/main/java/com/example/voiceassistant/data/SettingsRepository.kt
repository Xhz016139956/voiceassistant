package com.example.voiceassistant.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

/**
 * 轻量设置项。API Key 不在这里（模块 9 会放进 EncryptedSharedPreferences）。
 */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val ASSISTANT_ENABLED = booleanPreferencesKey("assistant_enabled")
        val TTS_MUTED = booleanPreferencesKey("tts_muted")
        val LAST_BASE_URL = stringPreferencesKey("llm_base_url")
        val LAST_MODEL = stringPreferencesKey("llm_model")
    }

    val assistantEnabled: Flow<Boolean> =
        context.settingsDataStore.data.map { it[Keys.ASSISTANT_ENABLED] ?: false }

    val ttsMuted: Flow<Boolean> =
        context.settingsDataStore.data.map { it[Keys.TTS_MUTED] ?: false }

    suspend fun setAssistantEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.ASSISTANT_ENABLED] = enabled }
    }

    suspend fun setTtsMuted(muted: Boolean) {
        context.settingsDataStore.edit { it[Keys.TTS_MUTED] = muted }
    }
}
