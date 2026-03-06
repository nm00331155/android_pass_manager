package com.securevault.app.service.otp

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class OtpNotificationListener : NotificationListenerService() {

    private val otpRegex = Regex("(?:code|otp|token|verification|認証|確認)[^0-9]*(\\d{4,8})", RegexOption.IGNORE_CASE)

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras ?: return
        val text = buildString {
            append(extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty())
            append(' ')
            append(extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty())
        }

        val code = otpRegex.find(text)?.groupValues?.getOrNull(1) ?: return
        Log.d(TAG, "OTP candidate from ${sbn.packageName}: $code")
    }

    private companion object {
        const val TAG = "OtpNotification"
    }
}
