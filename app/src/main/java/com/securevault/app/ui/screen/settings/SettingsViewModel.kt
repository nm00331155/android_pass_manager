package com.securevault.app.ui.screen.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.securevault.app.data.repository.CredentialRepository
import com.securevault.app.util.AutoLockManager
import com.securevault.app.util.ClipboardSettingsManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * 設定画面の状態管理を担当する ViewModel。
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    autoLockManager: AutoLockManager,
    private val clipboardSettingsManager: ClipboardSettingsManager,
    private val credentialRepository: CredentialRepository
) : ViewModel() {

    val autoLockTimeoutSeconds: StateFlow<Int> = autoLockManager.autoLockTimeoutSeconds
    private val setAutoLockTimeout = autoLockManager::setAutoLockTimeoutSeconds

    val clipboardClearTimeoutSeconds: StateFlow<Int> =
        clipboardSettingsManager.clipboardClearTimeoutSeconds

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 2)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

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
}
