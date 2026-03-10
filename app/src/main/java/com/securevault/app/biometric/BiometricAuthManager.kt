package com.securevault.app.biometric

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * BiometricPrompt を用いた認証処理を管理し、Compose から扱える状態を提供する。
 */
@Singleton
class BiometricAuthManager @Inject constructor() {

    private val _authState = MutableStateFlow<AuthUiState>(AuthUiState.Idle)
    val authState: StateFlow<AuthUiState> = _authState.asStateFlow()

    /**
     * 現在端末で利用可能な認証方法を評価する。
     */
    fun canAuthenticate(context: Context): AuthAvailability {
        val manager = BiometricManager.from(context)
        val result = manager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )

        return if (result == BiometricManager.BIOMETRIC_SUCCESS) {
            AuthAvailability(isAvailable = true)
        } else {
            AuthAvailability(
                isAvailable = false,
                message = mapBiometricError(result)
            )
        }
    }

    /**
     * 認証フローを開始する。
     */
    fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
        onFallback: () -> Unit
    ) {
        val manager = BiometricManager.from(activity)
        val strongBiometric = manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        val deviceCredential = manager.canAuthenticate(BiometricManager.Authenticators.DEVICE_CREDENTIAL)

        val authenticators = when {
            strongBiometric == BiometricManager.BIOMETRIC_SUCCESS -> {
                BiometricManager.Authenticators.BIOMETRIC_STRONG
            }
            deviceCredential == BiometricManager.BIOMETRIC_SUCCESS -> {
                _authState.value = AuthUiState.DeviceCredentialFallback
                onFallback()
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
            }
            else -> {
                val message = mapBiometricError(strongBiometric)
                _authState.value = AuthUiState.Error(message)
                onError(message)
                return
            }
        }

        val promptBuilder = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(authenticators)

        if (authenticators == BiometricManager.Authenticators.BIOMETRIC_STRONG) {
            promptBuilder.setNegativeButtonText(activity.getString(android.R.string.cancel))
        }

        val promptInfo = promptBuilder.build()

        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    _authState.value = AuthUiState.Success
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    val message = errString.toString()
                    _authState.value = AuthUiState.Error(message)
                    onError(message)
                }

                override fun onAuthenticationFailed() {
                    _authState.value = AuthUiState.Authenticating
                }
            }
        )

        _authState.value = AuthUiState.Authenticating
        prompt.authenticate(promptInfo)
    }

    /**
     * UI 状態を初期化する。
     */
    fun resetState() {
        _authState.value = AuthUiState.Idle
    }

    private fun mapBiometricError(code: Int): String {
        return when (code) {
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> "生体認証ハードウェアがありません。"
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> "生体認証ハードウェアが利用できません。"
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "生体情報が登録されていません。"
            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> "セキュリティ更新が必要です。"
            BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED -> "この端末ではサポートされていません。"
            BiometricManager.BIOMETRIC_STATUS_UNKNOWN -> "認証状態を判定できませんでした。"
            else -> "認証を利用できません。"
        }
    }
}

/**
 * 認証可否の判定結果。
 */
data class AuthAvailability(
    val isAvailable: Boolean,
    val message: String? = null
)

/**
 * 認証UIの状態。
 */
sealed interface AuthUiState {
    data object Idle : AuthUiState
    data object Authenticating : AuthUiState
    data object Success : AuthUiState
    data object DeviceCredentialFallback : AuthUiState
    data class Error(val message: String) : AuthUiState
}
