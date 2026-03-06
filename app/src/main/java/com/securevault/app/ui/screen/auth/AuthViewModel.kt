package com.securevault.app.ui.screen.auth

import android.content.Context
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import com.securevault.app.biometric.AuthUiState
import com.securevault.app.biometric.BiometricAuthManager
import com.securevault.app.util.AutoLockManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 認証画面の状態と認証開始処理を管理する ViewModel。
 */
@HiltViewModel
class AuthViewModel @Inject constructor(
    private val biometricAuthManager: BiometricAuthManager,
    private val autoLockManager: AutoLockManager
) : ViewModel() {

    val authState: StateFlow<AuthUiState> = biometricAuthManager.authState

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    /**
     * 現在の端末で認証機能が利用可能かを返す。
     */
    fun isAuthAvailable(context: Context): Boolean {
        return biometricAuthManager.canAuthenticate(context).isAvailable
    }

    /**
     * 生体認証または端末認証を開始する。
     */
    fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        onAuthenticated: () -> Unit
    ) {
        _errorMessage.value = null
        biometricAuthManager.authenticate(
            activity = activity,
            title = title,
            subtitle = subtitle,
            onSuccess = {
                autoLockManager.unlock()
                onAuthenticated()
            },
            onError = { message ->
                _errorMessage.value = message
            },
            onFallback = {
                // フォールバック状態は authState 経由で UI 側に反映する。
            }
        )
    }

    /** UI表示用のエラーメッセージを明示設定する。 */
    fun setError(message: String) {
        _errorMessage.value = message
    }

    /** エラーメッセージをクリアする。 */
    fun clearError() {
        _errorMessage.value = null
    }
}
