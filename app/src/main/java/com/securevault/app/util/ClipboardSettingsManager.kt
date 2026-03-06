package com.securevault.app.util

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private val Context.securitySettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "securevault_security_settings"
)

/**
 * クリップボード自動クリア秒数の設定を管理する。
 */
@Singleton
class ClipboardSettingsManager @Inject constructor(
    @ApplicationContext private val appContext: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _clipboardClearTimeoutSeconds = MutableStateFlow(DEFAULT_CLIPBOARD_CLEAR_TIMEOUT_SECONDS)
    val clipboardClearTimeoutSeconds: StateFlow<Int> = _clipboardClearTimeoutSeconds.asStateFlow()

    init {
        scope.launch {
            appContext.securitySettingsDataStore.data
                .map { prefs ->
                    prefs[CLIPBOARD_CLEAR_TIMEOUT_SECONDS_KEY] ?: DEFAULT_CLIPBOARD_CLEAR_TIMEOUT_SECONDS
                }
                .distinctUntilChanged()
                .collect { timeout ->
                    _clipboardClearTimeoutSeconds.value = timeout
                }
        }
    }

    /**
     * クリップボード自動クリア秒数を保存する。
     * -1: 無効, 1以上: 秒
     */
    suspend fun setClipboardClearTimeoutSeconds(timeoutSeconds: Int) {
        val sanitized = timeoutSeconds.coerceAtLeast(CLIPBOARD_CLEAR_DISABLED_SECONDS)
        appContext.securitySettingsDataStore.edit { settings ->
            settings[CLIPBOARD_CLEAR_TIMEOUT_SECONDS_KEY] = sanitized
        }
    }

    companion object {
        const val CLIPBOARD_CLEAR_DISABLED_SECONDS = -1
        const val DEFAULT_CLIPBOARD_CLEAR_TIMEOUT_SECONDS = 30

        private val CLIPBOARD_CLEAR_TIMEOUT_SECONDS_KEY =
            intPreferencesKey("clipboard_clear_timeout_seconds")
    }
}
