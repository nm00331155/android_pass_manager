package com.securevault.app.ui.screen.auth

import android.content.Context
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import com.securevault.app.biometric.AuthAvailability
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

    /** BiometricAuthManager が公開する認証状態。 */
    val authState: StateFlow<AuthUiState> = biometricAuthManager.authState

    private val _errorMessage = MutableStateFlow<String?>(null)
    /** UI表示用のエラーメッセージ。 */
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    /**
     * 現在端末で認証機能が利用可能かを返す。
     */
    fun isAuthAvailable(context: Context): Boolean {
        return getAuthAvailability(context).isAvailable
    }

    /**
     * 現在端末で利用可能な認証方式の判定結果を返す。
     */
    fun getAuthAvailability(context: Context): AuthAvailability {
        return biometricAuthManager.canAuthenticate(context)
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
        if (authState.value is AuthUiState.Authenticating) {
            return
        }

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

    /**
     * 認証画面表示時に前回の認証状態を持ち越さないよう初期化する。
     */
    fun prepareForEntry() {
        if (authState.value is AuthUiState.Authenticating) {
            return
        }

        biometricAuthManager.resetState()
        clearError()
    }

    /** UI表示用のエラーメッセージを明示設定する。 */
    fun setError(message: String) {
        _errorMessage.value = message
    }

    /** エラーメッセージをクリアする。 */
    fun clearError() {
        _errorMessage.value = null
    }

    /**
     * 認証状態をリトライ可能な初期状態へ戻す。
     */
    fun resetForRetry() {
        biometricAuthManager.resetState()
        clearError()
    }
}
