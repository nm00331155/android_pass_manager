package com.securevault.app.service.otp

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.autofill.Dataset
import android.view.View
import android.view.autofill.AutofillId
import android.view.autofill.AutofillManager
import android.view.autofill.AutofillValue
import android.widget.Button
import android.widget.ProgressBar
import android.widget.RemoteViews
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.securevault.app.R
import com.securevault.app.data.store.SecuritySettingsPreferences
import com.securevault.app.data.store.securitySettingsDataStore
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.delay
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
    private lateinit var titleView: TextView
    private lateinit var messageView: TextView
    private lateinit var progressView: ProgressBar
    private lateinit var cancelButton: Button

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

        setContentView(R.layout.activity_sms_otp_waiting)
        setFinishOnTouchOutside(false)
        titleView = findViewById(R.id.title)
        messageView = findViewById(R.id.message)
        progressView = findViewById(R.id.progress)
        cancelButton = findViewById(R.id.actionCancel)
        renderState(
            title = getString(R.string.otp_waiting_for_code),
            message = getString(R.string.otp_waiting_message),
            showProgress = true
        )
        cancelButton.setOnClickListener {
            finishCanceled()
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

            val smsStatus = if (smsEnabled) {
                otpManager.startSmsListening()
            } else {
                null
            }

            when (
                OtpListeningPolicy.resolve(
                    smsEnabled = smsEnabled,
                    notificationEnabled = notificationEnabled,
                    clipboardEnabled = clipboardEnabled,
                    smsStatus = smsStatus
                )
            ) {
                OtpStartOutcome.NO_SOURCE_ENABLED -> {
                    showTerminalMessageAndClose(
                        title = getString(R.string.otp_unavailable_title),
                        message = getString(R.string.otp_no_source_enabled_message)
                    )
                    return@launch
                }

                OtpStartOutcome.SMS_PERMISSION_DENIED -> {
                    showTerminalMessageAndClose(
                        title = getString(R.string.otp_unavailable_title),
                        message = getString(R.string.otp_sms_permission_denied_message)
                    )
                    return@launch
                }

                OtpStartOutcome.SMS_UNAVAILABLE -> {
                    showTerminalMessageAndClose(
                        title = getString(R.string.otp_unavailable_title),
                        message = getString(R.string.otp_sms_unavailable_message)
                    )
                    return@launch
                }

                OtpStartOutcome.WAIT_FOR_CODE -> Unit
            }

            if (allowedSources.isEmpty()) {
                finishCanceled()
                return@launch
            }

            otpManager.getRecentOtp(
                allowedSources = allowedSources,
                maxAgeMs = RECENT_OTP_MAX_AGE_MS
            )?.let { recentEvent ->
                renderState(
                    title = getString(R.string.otp_detected_title),
                    message = getString(R.string.otp_detected, recentEvent.code),
                    showProgress = false
                )
                delay(RESULT_MESSAGE_VISIBLE_MS)
                returnOtpResult(recentEvent.code)
                return@launch
            }

            if (clipboardEnabled) {
                otpManager.startClipboardMonitoring()
                clipboardMonitoringStarted = true
            }

            val event = withTimeoutOrNull(LISTEN_TIMEOUT_MS) {
                otpManager.otpEvents
                    .filter { it.source in allowedSources }
                    .first()
            }

            if (event == null) {
                showTerminalMessageAndClose(
                    title = getString(R.string.otp_timeout_title),
                    message = getString(R.string.otp_timeout_message)
                )
                return@launch
            }

            renderState(
                title = getString(R.string.otp_detected_title),
                message = getString(R.string.otp_detected, event.code),
                showProgress = false
            )
            delay(RESULT_MESSAGE_VISIBLE_MS)
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

    private fun renderState(
        title: CharSequence,
        message: CharSequence,
        showProgress: Boolean
    ) {
        titleView.text = title
        messageView.text = message
        progressView.visibility = if (showProgress) View.VISIBLE else View.GONE
    }

    private suspend fun showTerminalMessageAndClose(
        title: CharSequence,
        message: CharSequence
    ) {
        renderState(
            title = title,
            message = message,
            showProgress = false
        )
        delay(ERROR_MESSAGE_VISIBLE_MS)
        finishCanceled()
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
        private const val RECENT_OTP_MAX_AGE_MS = 5 * 60_000L
        private const val RESULT_MESSAGE_VISIBLE_MS = 450L
        private const val ERROR_MESSAGE_VISIBLE_MS = 1_200L

        const val EXTRA_OTP_AUTOFILL_ID = "otpAutofillId"
    }
}
