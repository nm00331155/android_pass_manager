package com.securevault.app.service.otp

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * 複数ソース（SMS/通知/クリップボード）から OTP イベントを統合して公開する。
 */
@Singleton
class OtpManager @Inject constructor(
    private val smsOtpManager: SmsOtpManager,
    private val clipboardOtpDetector: ClipboardOtpDetector
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _otpEvents = MutableSharedFlow<OtpEvent>(replay = 0, extraBufferCapacity = 3)
    val otpEvents: SharedFlow<OtpEvent> = _otpEvents.asSharedFlow()

    init {
        scope.launch {
            smsOtpManager.otpResult.collect { code ->
                onOtpDetected(code, OtpSource.SMS)
            }
        }

        scope.launch {
            clipboardOtpDetector.detectedOtp.collect { code ->
                onOtpDetected(code, OtpSource.CLIPBOARD)
            }
        }
    }

    /**
     * 外部（NotificationListener 等）から OTP を通知する。
     */
    fun onOtpDetected(code: String, source: OtpSource) {
        _otpEvents.tryEmit(
            OtpEvent(
                code = code,
                source = source,
                timestamp = System.currentTimeMillis()
            )
        )
    }

    /**
     * SMS OTP 監視を開始する。
     */
    suspend fun startSmsListening(): SmsOtpStatus {
        return smsOtpManager.startListening()
    }

    /**
     * クリップボード監視を開始する。
     */
    fun startClipboardMonitoring() {
        clipboardOtpDetector.startMonitoring()
    }

    /**
     * クリップボード監視を停止する。
     */
    fun stopClipboardMonitoring() {
        clipboardOtpDetector.stopMonitoring()
    }
}

/**
 * OTP 検出イベント。
 */
data class OtpEvent(
    val code: String,
    val source: OtpSource,
    val timestamp: Long
)

/**
 * OTP 検出ソース。
 */
enum class OtpSource {
    SMS,
    NOTIFICATION,
    CLIPBOARD
}
