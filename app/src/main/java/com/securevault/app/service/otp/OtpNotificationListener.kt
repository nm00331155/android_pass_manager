package com.securevault.app.service.otp

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.securevault.app.data.store.SecuritySettingsPreferences
import com.securevault.app.data.store.securitySettingsDataStore
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * 通知本文から OTP を抽出する NotificationListenerService。
 * Android 15+ では sensitive 通知の本文が隠蔽され、検出できない場合がある。
 */
@AndroidEntryPoint
class OtpNotificationListener : NotificationListenerService() {

    @Inject
    lateinit var otpManager: OtpManager

    @Inject
    @ApplicationContext
    lateinit var appContext: Context

    /**
     * 通知受信時に本文から OTP を抽出し、OtpManager へ通知する。
     */
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!isNotificationOtpEnabled()) {
            return
        }

        val extras = sbn.notification.extras
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: return

        val otp = extractOtp(text) ?: return
        otpManager.onOtpDetected(otp, OtpSource.NOTIFICATION)
        Log.d(TAG, "OTP detected from ${sbn.packageName}: $otp")
    }

    private fun extractOtp(text: String): String? {
        val contextualPattern = Regex(
            "(?:認証コード|確認コード|ワンタイム|OTP|code|verify)[\\s:：]*?(\\d{4,8})",
            RegexOption.IGNORE_CASE
        )
        val contextMatch = contextualPattern.find(text)
        if (contextMatch != null) {
            return contextMatch.groupValues[1]
        }

        val fallback = Regex("\\b(\\d{4,8})\\b")
        return fallback.find(text)?.groupValues?.getOrNull(1)
    }

    private fun isNotificationOtpEnabled(): Boolean {
        return runCatching {
            runBlocking {
                appContext.securitySettingsDataStore.data.first()[SecuritySettingsPreferences.OTP_NOTIFICATION_ENABLED_KEY]
                    ?: SecuritySettingsPreferences.DEFAULT_OTP_NOTIFICATION_ENABLED
            }
        }.getOrDefault(SecuritySettingsPreferences.DEFAULT_OTP_NOTIFICATION_ENABLED)
    }

    private companion object {
        const val TAG = "OtpNotification"
    }
}
