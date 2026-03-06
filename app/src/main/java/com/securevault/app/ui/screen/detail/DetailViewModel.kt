package com.securevault.app.ui.screen.detail

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.securevault.app.data.repository.CredentialRepository
import com.securevault.app.data.repository.model.Credential
import com.securevault.app.util.ClipboardSettingsManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 詳細画面の表示・操作状態を管理する ViewModel。
 */
@HiltViewModel
class DetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val credentialRepository: CredentialRepository,
    private val clipboardSettingsManager: ClipboardSettingsManager
) : ViewModel() {

    private val credentialId: Long = savedStateHandle[ARG_CREDENTIAL_ID] ?: NO_CREDENTIAL_ID

    val credential: StateFlow<Credential?> = if (credentialId >= 0L) {
        credentialRepository.getById(credentialId)
    } else {
        flowOf(null)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = null
    )

    private val _passwordVisible = MutableStateFlow(false)
    val passwordVisible: StateFlow<Boolean> = _passwordVisible.asStateFlow()

    private val _events = MutableSharedFlow<DetailEvent>(extraBufferCapacity = 4)
    val events = _events.asSharedFlow()

    /**
     * パスワード表示/非表示を切り替える。
     */
    fun togglePasswordVisibility() {
        _passwordVisible.value = !_passwordVisible.value
    }

    /**
     * 文字列をクリップボードへコピーする。
     */
    fun copyToClipboard(context: Context, text: String, label: String) {
        val appContext = context.applicationContext
        val clipboardManager =
            appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        clipboardManager.setPrimaryClip(ClipData.newPlainText(label, text))

        viewModelScope.launch {
            _events.emit(DetailEvent.Message("$label をコピーしました。"))

            val timeoutSeconds = clipboardSettingsManager.clipboardClearTimeoutSeconds.first()
            if (timeoutSeconds <= 0) {
                return@launch
            }

            _events.emit(DetailEvent.Message("${timeoutSeconds}秒後に自動クリアされます。"))
            delay(timeoutSeconds * 1000L)

            val currentText = clipboardManager.primaryClip
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)
                ?.coerceToText(appContext)
                ?.toString()

            if (currentText == text) {
                clipboardManager.setPrimaryClip(ClipData.newPlainText(label, ""))
            }
        }
    }

    /**
     * 表示中の認証情報を削除する。
     */
    fun deleteCredential() {
        viewModelScope.launch {
            val current = credential.value ?: return@launch
            runCatching {
                credentialRepository.delete(current.id)
            }.onSuccess {
                _events.emit(DetailEvent.NavigateBack)
            }.onFailure { throwable ->
                _events.emit(
                    DetailEvent.Message(
                        throwable.message ?: "削除に失敗しました。"
                    )
                )
            }
        }
    }

    private companion object {
        const val ARG_CREDENTIAL_ID = "credentialId"
        const val NO_CREDENTIAL_ID = -1L
    }
}

/**
 * 詳細画面向けの単発イベント。
 */
sealed interface DetailEvent {
    data class Message(val text: String) : DetailEvent
    data object NavigateBack : DetailEvent
}
