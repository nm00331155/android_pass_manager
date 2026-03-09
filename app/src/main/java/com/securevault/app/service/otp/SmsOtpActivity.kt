package com.securevault.app.service.otp

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.autofill.Dataset
import android.view.autofill.AutofillId
import android.view.autofill.AutofillManager
import android.view.autofill.AutofillValue
import android.widget.Toast
import android.widget.RemoteViews
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.securevault.app.R
import com.securevault.app.data.store.SecuritySettingsPreferences
import com.securevault.app.data.store.securitySettingsDataStore
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * SMS OTP 取得を行い、Autofill 用 Dataset を返却するアクティビティ。
 */
@AndroidEntryPoint
class SmsOtpActivity : ComponentActivity() {

    @Inject
    lateinit var otpManager: OtpManager

    @Inject
    @ApplicationContext
    lateinit var appContext: Context

    private var otpAutofillId: AutofillId? = null
    private var clipboardMonitoringStarted = false

    /**
     * OTP 取得を開始し、結果を Autofill に返す。
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        otpAutofillId = getAutofillIdExtra(EXTRA_OTP_AUTOFILL_ID)
        if (otpAutofillId == null) {
            finishCanceled()
            return
        }

        lifecycleScope.launch {
            val settings = appContext.securitySettingsDataStore.data.first()
            val smsEnabled = settings[SecuritySettingsPreferences.OTP_SMS_ENABLED_KEY]
                ?: SecuritySettingsPreferences.DEFAULT_OTP_SMS_ENABLED
            val notificationEnabled = settings[SecuritySettingsPreferences.OTP_NOTIFICATION_ENABLED_KEY]
                ?: SecuritySettingsPreferences.DEFAULT_OTP_NOTIFICATION_ENABLED
            val clipboardEnabled = settings[SecuritySettingsPreferences.OTP_CLIPBOARD_ENABLED_KEY]
                ?: SecuritySettingsPreferences.DEFAULT_OTP_CLIPBOARD_ENABLED

            val allowedSources = buildSet {
                if (smsEnabled) add(OtpSource.SMS)
                if (notificationEnabled) add(OtpSource.NOTIFICATION)
                if (clipboardEnabled) add(OtpSource.CLIPBOARD)
            }

            if (allowedSources.isEmpty()) {
                finishCanceled()
                return@launch
            }

            if (clipboardEnabled) {
                otpManager.startClipboardMonitoring()
                clipboardMonitoringStarted = true
            }
            if (smsEnabled) {
                otpManager.startSmsListening()
            }

            showMessage(getString(R.string.otp_waiting_for_code))

            val event = withTimeoutOrNull(LISTEN_TIMEOUT_MS) {
                otpManager.otpEvents
                    .filter { it.source in allowedSources }
                    .first()
            }

            if (event == null) {
                finishCanceled()
                return@launch
            }

            showMessage(getString(R.string.otp_detected, event.code))
            returnOtpResult(event.code)
        }
    }

    /**
     * 受信レシーバーを確実に解除する。
     */
    override fun onDestroy() {
        if (clipboardMonitoringStarted) {
            otpManager.stopClipboardMonitoring()
            clipboardMonitoringStarted = false
        }
        super.onDestroy()
    }

    private fun returnOtpResult(code: String) {
        val targetId = otpAutofillId ?: return finishCanceled()

        val dataset = Dataset.Builder(
            RemoteViews(packageName, R.layout.autofill_otp_item).apply {
                setTextViewText(R.id.label, getString(R.string.otp_detected, code))
            }
        ).apply {
            setValue(targetId, AutofillValue.forText(code))
        }.build()

        val resultIntent = Intent().apply {
            putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, dataset)
        }

        setResult(RESULT_OK, resultIntent)
        finish()
    }

    private fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun getAutofillIdExtra(key: String): AutofillId? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(key, AutofillId::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(key)
        }
    }

    private fun finishCanceled() {
        setResult(RESULT_CANCELED)
        finish()
    }

    companion object {
        private const val TAG = "SmsOtpActivity"
        private const val LISTEN_TIMEOUT_MS = 90_000L

        const val EXTRA_OTP_AUTOFILL_ID = "otpAutofillId"
    }
}
