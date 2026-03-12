package com.securevault.app.service.otp

import android.content.ClipboardManager
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * クリップボード変更から OTP を検出する検出器。
 */
@Singleton
class ClipboardOtpDetector @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val clipboardManager =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private val _detectedOtp = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 1)
    val detectedOtp: SharedFlow<String> = _detectedOtp.asSharedFlow()

    private var listener: ClipboardManager.OnPrimaryClipChangedListener? = null

    /**
     * クリップボード変更リスナーを開始する。
     */
    fun startMonitoring() {
        if (listener != null) {
            return
        }

        val newListener = ClipboardManager.OnPrimaryClipChangedListener {
            emitCurrentOtpIfPresent()
        }

        listener = newListener
        clipboardManager.addPrimaryClipChangedListener(newListener)
    }

    /**
     * クリップボード変更リスナーを停止する。
     */
    fun stopMonitoring() {
        val currentListener = listener ?: return
        clipboardManager.removePrimaryClipChangedListener(currentListener)
        listener = null
    }

    fun getCurrentClipboardText(): String? {
        return readClipboardText()
    }

    fun detectCurrentOtp(): String? {
        val text = readClipboardText() ?: return null
        return extractOtp(text)
    }

    private fun emitCurrentOtpIfPresent(): String? {
        val otp = detectCurrentOtp() ?: return null
        _detectedOtp.tryEmit(otp)
        return otp
    }

    private fun readClipboardText(): String? {
        val clip = clipboardManager.primaryClip ?: return null
        return clip.getItemAt(0)?.text?.toString()
    }

    private fun extractOtp(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.length > 20) return null

        val pattern = Regex("^\\d{4,8}$")
        return if (pattern.matches(trimmed)) trimmed else null
    }
}
