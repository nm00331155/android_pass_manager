package com.securevault.app.ui.screen.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.securevault.app.data.repository.CredentialRepository
import com.securevault.app.data.store.securitySettingsDataStore
import com.securevault.app.util.AutoLockManager
import com.securevault.app.util.ClipboardSettingsManager
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 設定画面の状態管理を担当する ViewModel。
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    autoLockManager: AutoLockManager,
    private val clipboardSettingsManager: ClipboardSettingsManager,
    private val credentialRepository: CredentialRepository
) : ViewModel() {

    val autoLockTimeoutSeconds: StateFlow<Int> = autoLockManager.autoLockTimeoutSeconds
    private val setAutoLockTimeout = autoLockManager::setAutoLockTimeoutSeconds

    val clipboardClearTimeoutSeconds: StateFlow<Int> =
        clipboardSettingsManager.clipboardClearTimeoutSeconds

    private val _otpSmsEnabled = MutableStateFlow(DEFAULT_OTP_SMS_ENABLED)
    val otpSmsEnabled: StateFlow<Boolean> = _otpSmsEnabled.asStateFlow()

    private val _otpNotificationEnabled = MutableStateFlow(DEFAULT_OTP_NOTIFICATION_ENABLED)
    val otpNotificationEnabled: StateFlow<Boolean> = _otpNotificationEnabled.asStateFlow()

    private val _otpClipboardEnabled = MutableStateFlow(DEFAULT_OTP_CLIPBOARD_ENABLED)
    val otpClipboardEnabled: StateFlow<Boolean> = _otpClipboardEnabled.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 2)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    init {
        observeOtpSettings()
    }

    /**
     * 自動ロック設定を更新する。
     */
    fun updateAutoLockTimeoutSeconds(timeoutSeconds: Int) {
        viewModelScope.launch {
            setAutoLockTimeout(timeoutSeconds)
        }
    }

    /**
     * クリップボード自動クリア設定を更新する。
     */
    fun updateClipboardClearTimeoutSeconds(timeoutSeconds: Int) {
        viewModelScope.launch {
            clipboardSettingsManager.setClipboardClearTimeoutSeconds(timeoutSeconds)
        }
    }

    /**
     * SMS OTP 自動入力設定を更新する。
     */
    fun updateOtpSmsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            appContext.securitySettingsDataStore.edit { settings ->
                settings[OTP_SMS_ENABLED_KEY] = enabled
            }
        }
    }

    /**
     * 通知 OTP 読取設定を更新する。
     */
    fun updateOtpNotificationEnabled(enabled: Boolean) {
        viewModelScope.launch {
            appContext.securitySettingsDataStore.edit { settings ->
                settings[OTP_NOTIFICATION_ENABLED_KEY] = enabled
            }
        }
    }

    /**
     * クリップボード OTP 検出設定を更新する。
     */
    fun updateOtpClipboardEnabled(enabled: Boolean) {
        viewModelScope.launch {
            appContext.securitySettingsDataStore.edit { settings ->
                settings[OTP_CLIPBOARD_ENABLED_KEY] = enabled
            }
        }
    }

    /**
     * 保存済みの認証情報を全件削除する。
     */
    fun deleteAllData() {
        viewModelScope.launch {
            runCatching {
                credentialRepository.deleteAll()
            }.onSuccess {
                _messages.emit("すべてのデータを削除しました。")
            }.onFailure { throwable ->
                _messages.emit(throwable.message ?: "全件削除に失敗しました。")
            }
        }
    }

    private fun observeOtpSettings() {
        viewModelScope.launch {
            appContext.securitySettingsDataStore.data
                .map { settings -> settings[OTP_SMS_ENABLED_KEY] ?: DEFAULT_OTP_SMS_ENABLED }
                .distinctUntilChanged()
                .collect { enabled ->
                    _otpSmsEnabled.value = enabled
                }
        }

        viewModelScope.launch {
            appContext.securitySettingsDataStore.data
                .map {
                    settings ->
                    settings[OTP_NOTIFICATION_ENABLED_KEY] ?: DEFAULT_OTP_NOTIFICATION_ENABLED
                }
                .distinctUntilChanged()
                .collect { enabled ->
                    _otpNotificationEnabled.value = enabled
                }
        }

        viewModelScope.launch {
            appContext.securitySettingsDataStore.data
                .map { settings -> settings[OTP_CLIPBOARD_ENABLED_KEY] ?: DEFAULT_OTP_CLIPBOARD_ENABLED }
                .distinctUntilChanged()
                .collect { enabled ->
                    _otpClipboardEnabled.value = enabled
                }
        }
    }

    private companion object {
        const val DEFAULT_OTP_SMS_ENABLED = true
        const val DEFAULT_OTP_NOTIFICATION_ENABLED = false
        const val DEFAULT_OTP_CLIPBOARD_ENABLED = true

        val OTP_SMS_ENABLED_KEY = booleanPreferencesKey("otp_sms_enabled")
        val OTP_NOTIFICATION_ENABLED_KEY = booleanPreferencesKey("otp_notification_enabled")
        val OTP_CLIPBOARD_ENABLED_KEY = booleanPreferencesKey("otp_clipboard_enabled")
    }
}
